# build stage build the jar with all our resources
FROM openjdk:8-jdk as build

VOLUME /tmp
WORKDIR /

ADD . .

RUN apt-get update
RUN apt-get install -y maven
RUN mvn clean install -DskipTests