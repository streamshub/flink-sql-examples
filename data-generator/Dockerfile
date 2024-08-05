FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

USER root

RUN microdnf update \
    && microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y java-17-openjdk-headless shadow-utils \
    && microdnf clean all

# Set JAVA_HOME env var
ENV JAVA_HOME /usr/lib/jvm/jre-17

ADD target/data-generator-*.jar /data-generator.jar

CMD ["java", "-jar", "data-generator.jar"]