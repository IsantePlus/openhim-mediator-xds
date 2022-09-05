# build stage build the jar with all our resources
FROM openjdk:8-jdk as build

VOLUME /tmp
WORKDIR /

ADD . .

RUN apt-get update
RUN apt-get install -y maven
RUN mvn clean install -DskipTests


RUN mv /$JAR_PATH /mediator-xds-1.0.3-jar-with-dependencies.jar

# package stage
FROM openjdk:8-jdk-alpine
WORKDIR /
# copy only the built jar and nothing else
COPY --from=build /mediator-xds-1.0.3-jar-with-dependencies /


ENTRYPOINT java -jar mediator-xds-1.0.3-jar-with-dependencies.jar --conf mediator.properties