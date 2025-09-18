FROM amazoncorretto:23

WORKDIR /usr/src/app

ARG PROJECT_NAME
COPY ./build/libs/${PROJECT_NAME}.jar ./${PROJECT_NAME}.jar