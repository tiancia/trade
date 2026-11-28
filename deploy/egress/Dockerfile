ARG ALPINE_IMAGE=alpine:3.22
FROM ${ALPINE_IMAGE}

RUN apk add --no-cache openssh-client netcat-openbsd \
    && addgroup -S -g 10001 egress \
    && adduser -S -D -H -u 10001 -G egress egress

COPY --chmod=0755 entrypoint.sh /usr/local/bin/egress-entrypoint

USER 10001:10001
EXPOSE 13128

# A listening SSH forward does not prove that Squid or OKX is reachable.
HEALTHCHECK --interval=20s --timeout=3s --start-period=15s --retries=3 \
    CMD nc -z -w 2 127.0.0.1 13128 || exit 1

ENTRYPOINT ["/usr/local/bin/egress-entrypoint"]
