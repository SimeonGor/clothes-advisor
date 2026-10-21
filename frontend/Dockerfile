FROM caddy:2.11.4-alpine
COPY public/ /srv/
USER 10001:10001
EXPOSE 8080
CMD ["caddy", "file-server", "--root", "/srv", "--listen", ":8080"]
