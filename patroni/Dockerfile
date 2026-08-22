FROM postgres:16-bookworm

ARG PATRONI_VERSION=4.1.4
ARG PSYCOPG2_VERSION=2.9.10

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates curl \
    && python3 -m pip install --break-system-packages --no-cache-dir \
       "patroni[etcd3]==${PATRONI_VERSION}" \
       "psycopg2-binary==${PSYCOPG2_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

COPY patroni.yml /etc/patroni/patroni.yml
COPY entrypoint.sh /usr/local/bin/patroni-entrypoint.sh
COPY post-bootstrap.sh /usr/local/bin/post-bootstrap.sh

RUN chmod 0755 /usr/local/bin/patroni-entrypoint.sh /usr/local/bin/post-bootstrap.sh \
    && mkdir -p /var/lib/postgresql/data /var/run/postgresql \
    && chown -R postgres:postgres /var/lib/postgresql /var/run/postgresql

EXPOSE 5432 8008

ENTRYPOINT ["/usr/local/bin/patroni-entrypoint.sh"]
