FROM redis:8.8.0-alpine@sha256:9d317178eceac8454a2284a9e6df2466b93c745529947f0cd42a0fa9609d7005

COPY redis-entrypoint.sh /usr/local/bin/mianba-redis-entrypoint.sh
RUN chmod 0555 /usr/local/bin/mianba-redis-entrypoint.sh

ENTRYPOINT ["/bin/sh", "/usr/local/bin/mianba-redis-entrypoint.sh"]
