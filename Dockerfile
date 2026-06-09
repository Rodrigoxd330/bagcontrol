FROM maven:3.9-eclipse-temurin-23-noble AS build
WORKDIR /home/app
COPY src ./src
COPY pom.xml .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:23-jdk-noble
WORKDIR /app
COPY --from=build /home/app/target/*.jar spring_rest_docker.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","spring_rest_docker.jar"]
