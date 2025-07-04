# --- Estágio 1: O Construtor (Builder) ---
# Usamos uma imagem oficial do Clojure que já vem com Leiningen e Java JDK.
# Esta imagem geralmente usa uma versão recente do JDK, por isso a compilação funciona.
FROM clojure:lein-2.11.2 AS builder

# Define o diretório de trabalho dentro do container.
WORKDIR /app

# Copia o project.clj primeiro para otimizar o cache de dependências.
COPY project.clj .

# Baixa todas as dependências do projeto.
RUN lein deps

# Copia todo o resto do código-fonte do projeto.
COPY . .

# Executa o comando para criar o pacote .jar final e auto-contido (uberjar).
RUN lein uberjar


# --- Estágio 2: O Executor (Runner) ---
# Usamos uma imagem oficial do Java, muito pequena, que só tem o necessário para rodar (JRE).
# Alpine é uma distribuição Linux mínima, o que torna nossa imagem final muito leve.

# CORREÇÃO APLICADA AQUI: Alterado de Java 17 para Java 21
FROM eclipse-temurin:21-jre-alpine

# Define o diretório de trabalho.
WORKDIR /app

# Copia APENAS o arquivo .jar final que foi gerado no Estágio 1 (builder).
COPY --from=builder /app/target/uberjar/notification-watcher-0.1.0-SNAPSHOT-standalone.jar ./app.jar

# Expõe a porta que a nossa aplicação vai usar. O Render vai mapear esta porta.
EXPOSE 8080

# O comando que será executado quando o container iniciar.
CMD ["java", "-jar", "app.jar"]
