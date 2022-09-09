# build stage build the jar with all our resources
FROM openjdk:8-jdk as build

VOLUME /tmp
WORKDIR /

ADD . .

RUN apt-get update
RUN apt-get install -y maven
RUN mvn clean install -DskipTests

COPY . /target/mediator-xds-1.0.3-jar-with-dependencies.jar /

ENTRYPOINT java -jar mediator-xds-1.0.3-jar-with-dependencies.jar --conf mediator.properties