+++
title = 'Securing Flink components and its REST API'
+++

> Note: This tutorial is mainly focused on securing Flink components and its REST API.
> 
> For detailed information on working with [Flink ETL Jobs](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/learn-flink/etl/) and [Session Clusters](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-main/docs/custom-resource/overview/#session-cluster-deployments), look at the [Interactive ETL example](../interactive-etl/_index.md).
>
> For detailed information on connecting to Apache Kafka securely using Flink SQL, look at the [Secure Kafka example](../secure-kafka/_index.md).

[Flink SQL](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/dev/table/overview/) is a powerful tool for data exploration, manipulation and inter-connection.
It allows you to access the power of Flink's distributed stream processing abilities with a familiar interface.
In this tutorial, we go over how to secure the communications between internal Flink components, and also how to secure communications between the Flink [JobManager REST API](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/ops/rest_api/) and clients.

We will do this by enabling TLS authentication on both the internal Flink components and the Flink REST API.
For internal Flink components, only mutual authentication (mTLS) is available. However, for the REST API, both TLS and mTLS are available (TLS is recommended, more details below).

The tutorial is based on the StreamsHub [Flink SQL Examples](https://github.com/streamshub/flink-sql-examples) repository and the code can be found under the [`tutorials/secure-flink`](https://github.com/streamshub/flink-sql-examples/tree/main/tutorials/secure-flink) directory.

> Note:
> - All the commands below are meant to be run from the `tutorials` directory.
> - Only relevant lines are included in the code blocks.
>   - The `tutorials/secure-flink` directory contains the complete files.
> - For greater detail on what is covered in this tutorial, you can read the following:
>   - [“SSL Setup” section in the Apache Flink documentation](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/deployment/security/security-ssl/)
>   - ["Flink TLS Example" in the Apache Flink Kubernetes Operator repository](https://github.com/apache/flink-kubernetes-operator/tree/release-1.12/examples/flink-tls-example)

## Internal mTLS, REST TLS

This is the default when TLS is enabled on both the internal Flink components and the REST API.

Set up the demo application:

```shell
# Note: This sets up a Kafka cluster with an mTLS listener, Flink,
#       recommendation-app (generates example data), etc.
# Note: This enables mTLS between Flink SQL and Kafka. This isn't
#       related to internal Flink components and/or the REST API.
SECURE_KAFKA=mTLS ./scripts/data-gen-setup.sh

# Note: This creates a self-signed CA, TLS certificate, and Secret with the truststore/keystore password
#       (we use this for both the internal Flink components and the REST API)
kubectl -n flink apply -f secure-kafka/selfsigned-ca.yaml
kubectl -n flink apply -f secure-flink/flink-internal-cert.yaml

# Get the password for the internal truststore and keystore
export INTERNAL_PASSWORD=`
  kubectl -n flink get secret flink-internal-cert-password \
    -o jsonpath='{.data.password}' | base64 --decode
  `

# Note: This creates a standalone Flink job that connects to the mTLS Kafka listener
#       and copies 10 records from an existing topic to a newly created one
cat secure-flink/tls-rest/standalone-etl-secure-deployment.yaml | envsubst | kubectl -n flink apply -f -
```

`standalone-etl-secure-deployment.yaml` contains the following configuration:

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: standalone-etl-secure
spec:
  flinkConfiguration:
    # Internal mTLS (mutual auth between Flink components)
    security.ssl.internal.enabled: 'true'
    
    security.ssl.internal.keystore: /opt/flink/flink-internal-cert/keystore.p12
    security.ssl.internal.keystore-password: ${INTERNAL_PASSWORD}
    security.ssl.internal.keystore-type: PKCS12
    
    security.ssl.internal.key-password: ${INTERNAL_PASSWORD}
    
    security.ssl.internal.truststore: /opt/flink/flink-internal-cert/truststore.p12
    security.ssl.internal.truststore-password: ${INTERNAL_PASSWORD}
    security.ssl.internal.truststore-type: PKCS12
    
    # REST API TLS (non-mutual auth)
    security.ssl.rest.enabled: 'true'
    
    security.ssl.rest.keystore: /opt/flink/flink-internal-cert/keystore.p12
    security.ssl.rest.keystore-password: ${INTERNAL_PASSWORD}
    security.ssl.rest.keystore-type: PKCS12
    
    security.ssl.rest.key-password: ${INTERNAL_PASSWORD}
    
    security.ssl.rest.truststore: /opt/flink/flink-internal-cert/truststore.p12
    security.ssl.rest.truststore-password: ${INTERNAL_PASSWORD}
    security.ssl.rest.truststore-type: PKCS12
    
    # Secrets
    kubernetes.secrets: 'flink-internal-cert:/opt/flink/flink-internal-cert'
```

You can verify that the demo is working by doing the following:

- Port forward the Flink Job Manager pod so we can access it:

    ```shell
    kubectl -n flink port-forward <job-manager-pod> 8081:8081
    ```
    
    The job manager pod will have the name format `standalone-etl-secure-<alphanumeric>`, your `kubectl` should tab-complete the name.
    If it doesn't then you can find the job manager name by running `kubectl -n flink get pods`.
    
- Verify the TLS certificate is being sent:
    
    ```shell
    # Display details about the certificate
    openssl s_client -showcerts -connect localhost:8081 </dev/null
    ```
    
- Make an API request to the [JobManager REST API](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/ops/rest_api/) and parse the JSON response with [`jq`](https://jqlang.org/) like so:
    
    ```shell
    # Note: There should only be one continuously running job
    RUNNING_JOB_ID=$(curl -s -k https://localhost:8081/jobs/ | \
      jq -r '.jobs[] | select(.status == "RUNNING") | .id')
    
    # Get the number of records written to the output table/topic
    curl -s -k https://localhost:8081/jobs/$RUNNING_JOB_ID | \
      jq '.vertices[] | select(.name | contains("Writer")) |
          {
            "write-records": .metrics."write-records",
            "write-records-complete": .metrics."write-records-complete"
          }'
    ```
  
    > Note: We pass the `-k` flag to connect insecurely for now. In the other sections, we go over how to verify the CA when connecting.

### REST mTLS proxy

[Using a proxy with mTLS is recommended instead of enabling mTLS on the REST API](https://nightlies.apache.org/flink/flink-docs-release-2.1/docs/deployment/security/security-ssl/#external--rest-connectivity), since proxies typically provide more authentication options and integrate better with existing infrastructure.

We will follow this approach by exposing Flink's REST API `Service` (with TLS enabled) using an `Ingress` (with mTLS enabled).
[There are many ingress controllers available](https://kubernetes.io/docs/concepts/services-networking/ingress-controllers/); we will use [Ingress NGINX Controller](https://github.com/kubernetes/ingress-nginx) because of its popularity.

[Install the ingress controller](https://kubernetes.github.io/ingress-nginx/deploy/):

```shell
# If using a real cluster:
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.13.2/deploy/static/provider/cloud/deploy.yaml

# If using minikube:
minikube addons enable ingress
```

Create TLS certificates for the proxy server and client:

```shell
kubectl -n flink apply -f secure-flink/tls-rest/mtls-proxy/flink-rest-proxy-server-cert.yaml
kubectl -n flink apply -f secure-flink/tls-rest/mtls-proxy/flink-rest-proxy-client-cert.yaml
```

Create the `Ingress` / proxy:

```shell
kubectl -n flink apply -f secure-flink/tls-rest/mtls-proxy/standalone-etl-secure-rest-mtls-ingress.yaml
```

The `Ingress` is configured as follows:

> Note: For greater detail on mTLS and Ingress NGINX Controller, read the following:
> - ["Client Certificate Authentication" section in the Ingress NGINX Controller documentation](https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#client-certificate-authentication)
> - ["TLS/HTTPS" section in the Ingress NGINX Controller documentation](https://kubernetes.github.io/ingress-nginx/user-guide/tls/)

```shell
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: standalone-etl-secure-rest-mtls-ingress
  annotations:
    # Use HTTPS between Ingress and REST API (since TLS is enabled for the REST API)
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
    # Enable client authentication
    nginx.ingress.kubernetes.io/auth-tls-verify-client: "on"
    # Specify Secret containing our client certificate
    nginx.ingress.kubernetes.io/auth-tls-secret: flink/flink-rest-proxy-client-cert
spec:
  # Enable HTTPS/TLS for our Ingress
  tls:
    - hosts:
        - standalone-etl-secure-rest-mtls-ingress.example
      # Specify Secret containing our proxy's TLS certificate
      secretName: flink-rest-proxy-server-cert
  rules:
    # Point Ingress to Flink's REST API Service
    - host: standalone-etl-secure-rest-mtls-ingress.example
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: standalone-etl-secure-rest
                port:
                  number: 8081
```

We can connect to the proxy by doing the following:

```shell
# Generate tmp directories for the certs
mkdir /tmp/flink-rest-proxy-server-cert
mkdir /tmp/flink-rest-proxy-client-cert

# Get the proxy's CA cert so we can authenticate the proxy
kubectl -n flink get secret flink-rest-proxy-server-cert \
    -o jsonpath='{.data.ca\.crt}' | base64 --decode \
    > /tmp/flink-rest-proxy-server-cert/ca.crt

# Get the client's (our) cert so the proxy can authenticate us
kubectl -n flink get secret flink-rest-proxy-client-cert \
    -o jsonpath='{.data.tls\.crt}' | base64 --decode \
    > /tmp/flink-rest-proxy-client-cert/tls.crt

# Get the client's (our) cert key
kubectl -n flink get secret flink-rest-proxy-client-cert \
    -o jsonpath='{.data.tls\.key}' | base64 --decode \
    > /tmp/flink-rest-proxy-client-cert/tls.key

# (If using minikube) tunnel so we can reach the Ingress 
minikube tunnel

# Define cURL params for connecting to proxy securely
CURL_PROXY_PARAMS=(
  # We don't care about the progress meter
  --silent
  # Pass our cert so the proxy can authenticate us
  --cert /tmp/flink-rest-proxy-client-cert/tls.crt
  --key /tmp/flink-rest-proxy-client-cert/tls.key
  # Authenticate the proxy using the proxy's cert
  --cacert /tmp/flink-rest-proxy-server-cert/ca.crt
  # If not using minikube, remove this parameter (it tells cURL to resolve our domain to minikube's IP).
  --resolve "standalone-etl-secure-rest-mtls-ingress.example:443:$( minikube ip )"
)

# Get job ID
RUNNING_JOB_ID=$(curl "${CURL_PROXY_PARAMS[@]}" https://standalone-etl-secure-rest-mtls-ingress.example/jobs/ | \
  jq -r '.jobs[] | select(.status == "RUNNING") | .id')

# Verify 10 records were written
curl "${CURL_PROXY_PARAMS[@]}" https://standalone-etl-secure-rest-mtls-ingress.example/jobs/$RUNNING_JOB_ID | \
  jq '.vertices[] | select(.name | contains("Writer")) |
      {
        "write-records": .metrics."write-records",
        "write-records-complete": .metrics."write-records-complete"
      }'
```

## Internal mTLS, REST mTLS

> Note: As mentioned/performed above, using a proxy for mTLS is recommended instead of the following.

Set up the demo application:

```shell
SECURE_KAFKA=mTLS ./scripts/data-gen-setup.sh

kubectl -n flink apply -f secure-kafka/selfsigned-ca.yaml
kubectl -n flink apply -f secure-flink/flink-internal-cert.yaml

# Create TLS certificates for the REST API server and client
kubectl -n flink apply -f secure-flink/mtls-rest/flink-rest-server-cert.yaml
kubectl -n flink apply -f secure-flink/mtls-rest/flink-rest-client-cert.yaml

# Get the password for the internal truststore and keystore
export INTERNAL_PASSWORD=`
  kubectl -n flink get secret flink-internal-cert-password \
    -o jsonpath='{.data.password}' | base64 --decode
  `

# Get the password for the REST API keystore
export REST_SERVER_KEYSTORE_PASSWORD=`
  kubectl -n flink get secret flink-rest-server-cert-password \
    -o jsonpath='{.data.password}' | base64 --decode
  `

# Get the password for the REST API truststore
export REST_CLIENT_TRUSTSTORE_PASSWORD=`
  kubectl -n flink get secret flink-rest-client-cert-password \
    -o jsonpath='{.data.password}' | base64 --decode
  `

cat secure-flink/mtls-rest/standalone-etl-secure-deployment.yaml | envsubst | kubectl -n flink apply -f -
```

`standalone-etl-secure-deployment.yaml` contains the following configuration:

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: standalone-etl-secure
spec:
  flinkConfiguration:
    # Internal mTLS
    # ...same as TLS example above
    
    # REST API mTLS (mutual auth)
    security.ssl.rest.enabled: 'true'
    
    security.ssl.rest.authentication-enabled: 'true' # Enable mutual authentication
    
    security.ssl.rest.keystore: /opt/flink/flink-rest-server-cert/keystore.p12
    security.ssl.rest.keystore-password: ${REST_SERVER_KEYSTORE_PASSWORD}
    security.ssl.rest.keystore-type: PKCS12
    
    security.ssl.rest.key-password: ${REST_SERVER_KEYSTORE_PASSWORD}
    
    security.ssl.rest.truststore: /opt/flink/flink-rest-client-cert/truststore.p12
    security.ssl.rest.truststore-password: ${REST_CLIENT_TRUSTSTORE_PASSWORD}
    security.ssl.rest.truststore-type: PKCS12
    
    # Secrets
    kubernetes.secrets: 'flink-internal-cert:/opt/flink/flink-internal-cert,flink-rest-server-cert:/opt/flink/flink-rest-server-cert,flink-rest-client-cert:/opt/flink/flink-rest-client-cert'
```

We can connect to the REST API by doing the following:

```shell
# Create a pod containing cURL and the relevant certificates mounted
kubectl -n flink apply -f secure-flink/mtls-rest/standalone-etl-secure-rest-client.yaml

# Get a shell to the pod's container
kubectl -n flink exec -it standalone-etl-secure-rest-client -- /bin/sh
  
# Define cURL params for connecting to REST API securely
CURL_MTLS_REST_PARAMS=(
  # We don't care about the progress meter
  --silent
  # Pass our cert so the server can authenticate us
  --cert /opt/flink-rest-client-cert/tls.crt
  --key /opt/flink-rest-client-cert/tls.key
  # Authenticate the server using the server's cert
  --cacert /opt/flink-rest-server-cert/ca.crt
)

# Get job ID
RUNNING_JOB_ID=$(curl "${CURL_MTLS_REST_PARAMS[@]}" https://standalone-etl-secure-rest.flink.svc:8081/jobs/ | \
  jq -r '.jobs[] | select(.status == "RUNNING") | .id')

# Verify 10 records were written
curl "${CURL_MTLS_REST_PARAMS[@]}" https://standalone-etl-secure-rest.flink.svc:8081/jobs/$RUNNING_JOB_ID | \
  jq '.vertices[] | select(.name | contains("Writer")) |
      {
        "write-records": .metrics."write-records",
        "write-records-complete": .metrics."write-records-complete"
      }'
```