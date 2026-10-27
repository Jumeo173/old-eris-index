FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/old-eris-index-2.0.1.jar app.jar
RUN mkdir -p /var/log/olderis-index
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
