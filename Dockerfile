# build stage build the jar with all our resources
FROM maven:3.6-jdk-8 as build

WORKDIR /app

ADD pom.xml ./

RUN mvn -pl verify

ADD src ./

RUN mvn clean package -DskipTests

FROM openjdk:8-jdk as run

COPY --from=build /app/target/mediator-xds-1.0.3-jar-with-dependencies.jar /mediator-xds-1.0.3-jar-with-dependencies.jar
COPY --from=build /app/src/main/resources/mediator.properties /mediator.properties

ENTRYPOINT java -jar /mediator-xds-1.0.3-jar-with-dependencies.jar --conf /mediator.properties
