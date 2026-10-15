FROM node:24-alpine AS client
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
COPY --from=client /app/frontend/dist ./src/main/resources/static
RUN mvn -B package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S tracksy && adduser -S tracksy -G tracksy && mkdir -p /app/uploads && chown -R tracksy:tracksy /app
WORKDIR /app
COPY --from=build /app/backend/target/tracksy-1.0.0.jar app.jar
USER tracksy
EXPOSE 4000
ENTRYPOINT ["java","-jar","app.jar"]
