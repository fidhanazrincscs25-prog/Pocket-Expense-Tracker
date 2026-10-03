FROM eclipse-temurin:21-jdk
WORKDIR /app
ADD https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar /app/h2.jar
COPY PocketServer.java .
COPY index.html ./web/index.html
RUN mkdir classes && javac -encoding UTF-8 -cp h2.jar -d classes PocketServer.java
ENV DB_DIR=/tmp/pocketdata
CMD ["java", "-cp", "classes:h2.jar", "PocketServer"]
