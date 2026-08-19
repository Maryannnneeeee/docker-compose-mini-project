# fx-monitor is plain static files — there is no build step and no toolchain to ship.
# nginx serves the page AND proxies /api to the API, so the browser stays same-origin.
FROM nginx:1.27-alpine

# Which host to proxy the API calls to. "fx-app-spring" is the compose service name;
# override it with -e API_HOST=... when you run the container on its own.
ENV API_HOST=fx-app-spring

# nginx renders anything in /etc/nginx/templates/*.template through envsubst at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Copy only what the browser needs — not the Dockerfile, not the README.
COPY index.html styles.css app.js /usr/share/nginx/html/

EXPOSE 80
