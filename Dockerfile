# build stage build the jar with all our resources
FROM openjdk:8-jdk as build

ARG VERSION
ARG JAR_PATH

VOLUME /tmp
WORKDIR /
ADD . .

RUN mvn install -DskipTests
RUN mv /$JAR_PATH /mediator-xds-1.0.3-jar-with-dependencies.jar

# package stage
FROM openjdk:8-jdk-alpine
WORKDIR /
# copy only the built jar and nothing else
COPY --from=build /mediator-xds-1.0.3-jar-with-dependencies.jar /

ENV VERSION=$VERSION
ENV JAVA_OPTS=-Dspring.profiles.active=production

EXPOSE 5000

ENTRYPOINT ["sh","-c","java -jar -Dspring.profiles.active=production /app.jar"]