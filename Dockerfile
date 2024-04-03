# docker run --rm --privileged --cap-add NET_ADMIN --net=container:redis traffic-shaper tc qdisc add dev eth0 root netem delay 5000ms
# docker run --rm --privileged --cap-add NET_ADMIN --net=container:redis traffic-shaper tc qdisc del dev eth0 root netem

FROM alpine:latest
RUN apk --no-cache add iproute2
CMD ["/bin/sh"]
