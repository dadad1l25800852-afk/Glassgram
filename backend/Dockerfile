FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' -o /youfree ./cmd/youfree-server

FROM alpine:3.21
RUN addgroup -g 10001 -S youfree && adduser -S -D -u 10001 -G youfree youfree
USER youfree
COPY --from=build /youfree /youfree
COPY --from=build /src/testmedia /testmedia
EXPOSE 8443
ENTRYPOINT ["/youfree"]
