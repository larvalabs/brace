# The load generator. Runs pinned to its own CPUs (compose.yml), on the same Docker network
# as the app.
FROM alpine:3.22
RUN apk add --no-cache wrk
ENTRYPOINT ["wrk"]
