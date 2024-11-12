FROM amazoncorretto:23

WORKDIR /cardSearch

ARG PROJECT_NAME=cardSearch

COPY ./build/libs/${PROJECT_NAME}.jar /usr/src/app/${PROJECT_NAME}.jar