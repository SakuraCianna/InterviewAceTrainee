FROM pgvector/pgvector:0.8.5-pg18-bookworm@sha256:12a379b47ad65289572ea0756efc11b7c241a6662833e8af7038cd3b73d647e0

COPY 001-create-app-role.sh /docker-entrypoint-initdb.d/001-create-app-role.sh
RUN chmod 0555 /docker-entrypoint-initdb.d/001-create-app-role.sh
