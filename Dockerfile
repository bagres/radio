FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

RUN apk add --no-cache python3 py3-pip

RUN pip3 install --no-cache-dir yt-dlp --break-system-packages

COPY target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]


#Como rodar:
#docker build -t radio .
#docker run -d -p 8080:8080 --name radio radio