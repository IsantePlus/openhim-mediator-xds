FROM openjdk:8-jdk as build

VOLUME /tmp
WORKDIR /
ADD . .

RUN ./gradlew --stacktrace clean build -x test
EXPOSE 5000
ENTRYPOINT ["sh","-c","java -jar /app.jar"]