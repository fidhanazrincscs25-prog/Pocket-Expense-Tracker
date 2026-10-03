import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pocket Expense Tracker - WEB version
 * Backend: Java (built-in HTTP server) + JDBC + H2 database
 * Frontend: web/index.html (HTML + CSS + JavaScript)
 */
public class PocketServer {

    // Online hosts (Render) give the port in the PORT variable; on your laptop it is 8080
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    // Database folder: your user folder on a laptop, or DB_DIR when hosted online
    static final String URL = "jdbc:h2:file:"
            + System.getenv().getOrDefault("DB_DIR", "~/PocketExpenseTrackerWeb")
            + "/tracker;DB_CLOSE_DELAY=-1";

    // login sessions: token -> user id (kept in memory)
    static final Map<String, Integer> sessions = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ database
    static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    static void initDb() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "username VARCHAR(50) NOT NULL UNIQUE, "
                    + "password_hash VARCHAR(64) NOT NULL, "
                    + "budget DECIMAL(10,2) DEFAULT 0 NOT NULL, "
                    + "period VARCHAR(10) DEFAULT 'MONTHLY' NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "user_id INT NOT NULL, "
                    + "type VARCHAR(10) NOT NULL, "
                    + "category VARCHAR(30) NOT NULL, "
                    + "note VARCHAR(100), "
                    + "amount DECIMAL(10,2) NOT NULL, "
                    + "tx_date DATE NOT NULL, "
                    + "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");
        }
    }

    static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------ small helpers
    /** Makes a safe JSON string: hello -> "hello" */
    static String q(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String err(String msg) {
        return "{\"ok\":false,\"error\":" + q(msg) + "}";
    }

    /** Reads  a=1&b=2  into a map */
    static Map<String, String> form(String s) throws Exception {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), "UTF-8"),
                  URLDecoder.decode(part.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    // ------------------------------------------------------------ request handling
    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                byte[] page = Files.readAllBytes(Paths.get("web", "index.html"));
                send(ex, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (path.startsWith("/api/")) {
                Map<String, String> p = form(ex.getRequestURI().getRawQuery());
                p.putAll(form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                String out = api(path, p);
                send(ex, 200, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 404, "text/plain", "Not found".getBytes());
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json; charset=utf-8",
                    err("Server error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    static String api(String path, Map<String, String> p) throws Exception {
        if (path.equals("/api/register")) return auth(p, true);
        if (path.equals("/api/login")) return auth(p, false);

        String token = p.get("token");
        Integer uid = (token == null) ? null : sessions.get(token);
        if (uid == null) return err("Please log in again.");

        switch (path) {
            case "/api/data":   return data(uid);
            case "/api/budget": return saveBudget(uid, p);
            case "/api/tx":     return addTx(uid, p);
            case "/api/delete": return deleteTx(uid, p);
            case "/api/logout": sessions.remove(token); return "{\"ok\":true}";
            default:            return err("Unknown request");
        }
    }

    // ------------------------------------------------------------ features
    static String auth(Map<String, String> p, boolean register) throws Exception {
        String u = p.getOrDefault("username", "").trim();
        String pw = p.getOrDefault("password", "");
        if (u.isEmpty() || pw.length() < 4)
            return err("Enter a username and a password of 4+ characters.");

        int id;
        try (Connection c = conn()) {
            if (register) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users(username, password_hash) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, u);
                    ps.setString(2, hash(pw));
                    try {
                        ps.executeUpdate();
                    } catch (SQLIntegrityConstraintViolationException e) {
                        return err("That username is taken. Try another.");
                    }
                    ResultSet k = ps.getGeneratedKeys();
                    k.next();
                    id = k.getInt(1);
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM users WHERE username=? AND password_hash=?")) {
                    ps.setString(1, u);
                    ps.setString(2, hash(pw));
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) return err("Wrong username or password.");
                    id = rs.getInt(1);
                }
            }
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, id);
        return "{\"ok\":true,\"token\":" + q(token) + "}";
    }

    static String saveBudget(int uid, Map<String, String> p) throws Exception {
        BigDecimal amount;
        try {
            amount = new BigDecimal(p.getOrDefault("amount", "").trim());
        } catch (NumberFormatException e) {
            return err("Enter a valid budget amount.");
        }
        if (amount.signum() <= 0) return err("Enter a valid budget amount.");
        String period = "WEEKLY".equals(p.get("period")) ? "WEEKLY" : "MONTHLY";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("UPDATE users SET budget=?, period=? WHERE id=?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, period);
            ps.setInt(3, uid);
            ps.executeUpdate();
        }
        return "{\"ok\":true}";
    }

    static String addTx(int uid, Map<String, String> p) throws Exception {
        BigDecimal amount;
        try {
            amount = new BigDecimal(p.getOrDefault("amount", "").trim());
        } catch (NumberFormatException e) {
            return err("Enter a valid amount.");
        }
        if (amount.signum() <= 0) return err("Enter a valid amount.");
        String type = "INCOME".equals(p.get("type")) ? "INCOME" : "EXPENSE";
        String cat = p.getOrDefault("category", "Others");
        if (cat.length() > 30) cat = cat.substring(0, 30);
        String note = p.getOrDefault("note", "").trim();
        if (note.length() > 100) note = note.substring(0, 100);

        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO transactions(user_id, type, category, note, amount, tx_date) VALUES(?,?,?,?,?,?)")) {
            ps.setInt(1, uid);
            ps.setString(2, type);
            ps.setString(3, cat);
            ps.setString(4, note);
            ps.setBigDecimal(5, amount);
            ps.setDate(6, java.sql.Date.valueOf(LocalDate.now()));
            ps.executeUpdate();
        }
        return "{\"ok\":true}";
    }

    static String deleteTx(int uid, Map<String, String> p) throws Exception {
        int id;
        try {
            id = Integer.parseInt(p.getOrDefault("id", ""));
        } catch (NumberFormatException e) {
            return err("Bad id.");
        }
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement("DELETE FROM transactions WHERE id=? AND user_id=?")) {
            ps.setInt(1, id);
            ps.setInt(2, uid);
            ps.executeUpdate();
        }
        return "{\"ok\":true}";
    }

    /** Everything the page needs to draw itself */
    static String data(int uid) throws Exception {
        try (Connection c = conn()) {
            String name = "";
            String period = "MONTHLY";
            BigDecimal budget = BigDecimal.ZERO;
            try (PreparedStatement ps = c.prepareStatement("SELECT username, budget, period FROM users WHERE id=?")) {
                ps.setInt(1, uid);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    name = rs.getString(1);
                    budget = rs.getBigDecimal(2);
                    period = rs.getString(3);
                }
            }
            LocalDate startDay = period.equals("WEEKLY")
                    ? LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : LocalDate.now().withDayOfMonth(1);
            java.sql.Date start = java.sql.Date.valueOf(startDay);

            // income and spent in this budget period
            BigDecimal inc = BigDecimal.ZERO, exp = BigDecimal.ZERO;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT type, SUM(amount) FROM transactions WHERE user_id=? AND tx_date>=? GROUP BY type")) {
                ps.setInt(1, uid);
                ps.setDate(2, start);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if (rs.getString(1).equals("INCOME")) inc = rs.getBigDecimal(2);
                    else exp = rs.getBigDecimal(2);
                }
            }
            int pct = 0;
            if (budget.signum() > 0)
                pct = exp.multiply(BigDecimal.valueOf(100)).divide(budget, 0, RoundingMode.HALF_UP).intValue();

            // spending by category
            StringBuilder cats = new StringBuilder("[");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT category, SUM(amount) FROM transactions "
                    + "WHERE user_id=? AND type='EXPENSE' AND tx_date>=? GROUP BY category ORDER BY SUM(amount) DESC")) {
                ps.setInt(1, uid);
                ps.setDate(2, start);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if (cats.length() > 1) cats.append(',');
                    cats.append("{\"c\":").append(q(rs.getString(1)))
                        .append(",\"v\":").append(rs.getBigDecimal(2).toPlainString()).append('}');
                }
            }
            cats.append(']');

            // history + monthly summary
            StringBuilder txs = new StringBuilder("[");
            Map<String, BigDecimal[]> months = new TreeMap<String, BigDecimal[]>(Comparator.reverseOrder());
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, tx_date, type, category, note, amount FROM transactions "
                    + "WHERE user_id=? ORDER BY tx_date DESC, id DESC")) {
                ps.setInt(1, uid);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String date = rs.getDate(2).toString();
                    String type = rs.getString(3);
                    BigDecimal amt = rs.getBigDecimal(6);
                    if (txs.length() > 1) txs.append(',');
                    txs.append("{\"id\":").append(rs.getInt(1))
                       .append(",\"date\":").append(q(date))
                       .append(",\"type\":").append(q(type))
                       .append(",\"cat\":").append(q(rs.getString(4)))
                       .append(",\"note\":").append(q(rs.getString(5)))
                       .append(",\"amt\":").append(amt.toPlainString()).append('}');
                    BigDecimal[] m = months.computeIfAbsent(date.substring(0, 7),
                            k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    int i = type.equals("INCOME") ? 0 : 1;
                    m[i] = m[i].add(amt);
                }
            }
            txs.append(']');

            StringBuilder mon = new StringBuilder("[");
            for (Map.Entry<String, BigDecimal[]> e : months.entrySet()) {
                if (mon.length() > 1) mon.append(',');
                mon.append("{\"m\":").append(q(e.getKey()))
                   .append(",\"i\":").append(e.getValue()[0].toPlainString())
                   .append(",\"e\":").append(e.getValue()[1].toPlainString()).append('}');
            }
            mon.append(']');

            return "{\"ok\":true,\"name\":" + q(name)
                    + ",\"period\":" + q(period)
                    + ",\"budget\":" + budget.toPlainString()
                    + ",\"income\":" + inc.toPlainString()
                    + ",\"spent\":" + exp.toPlainString()
                    + ",\"pct\":" + pct
                    + ",\"cats\":" + cats
                    + ",\"months\":" + mon
                    + ",\"tx\":" + txs + "}";
        }
    }

    // ------------------------------------------------------------ start the server
    public static void main(String[] args) throws Exception {
        initDb();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", PocketServer::handle);
        server.start();

        System.out.println("==================================================");
        System.out.println(" Pocket Expense Tracker (Java backend) is RUNNING");
        System.out.println(" On this laptop open:  http://localhost:" + PORT);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress())
                        System.out.println(" On your phone open:   http://" + a.getHostAddress() + ":" + PORT);
            }
        } catch (Exception ignored) { }
        System.out.println(" (phone and laptop must be on the same Wi-Fi)");
        System.out.println(" Press Ctrl+C in this window to stop.");
        System.out.println("==================================================");
    }
}
