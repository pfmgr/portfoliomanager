FROM searxng/searxng:latest

COPY --chown=searxng:searxng settings.yml /usr/local/searxng/portfoliomanager/settings.yml
COPY --chown=searxng:searxng limiter.toml /usr/local/searxng/portfoliomanager/limiter.toml
COPY --chown=searxng:searxng --chmod=755 entrypoint.sh /usr/local/bin/portfoliomanager-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/portfoliomanager-entrypoint.sh"]
