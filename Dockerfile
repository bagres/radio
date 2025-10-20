# Etapa 1: Build da aplicação
FROM maven:3.9.6-eclipse-temurin-17 AS build

# Define o diretório de trabalho dentro do container
WORKDIR /app

# Copia o arquivo de configuração Maven primeiro (melhora cache)
COPY pom.xml .

# Baixa as dependências (para aproveitar o cache do Docker)
RUN mvn dependency:go-offline

# Copia o restante do código-fonte
COPY src ./src

# Compila o projeto e gera o .jar
RUN mvn clean package -DskipTests

# Etapa 2: Imagem de execução
FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copia o jar gerado da etapa de build
COPY --from=build /app/target/*.jar app.jar

# Expõe a porta da aplicação
EXPOSE 8080

# Comando para executar a aplicação
ENTRYPOINT ["java", "-jar", "app.jar"]

#Como rodar:
#docker build -t radio .
#docker run -d -p 8080:8080 --name radio radio