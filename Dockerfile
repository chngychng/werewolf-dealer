FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . .

RUN mkdir -p out \
    && javac -encoding UTF-8 -d out src/com/example/werewolf/WerewolfDealer.java

CMD ["sh", "-c", "java -cp out com.example.werewolf.WerewolfDealer ${PORT:-8080}"]
