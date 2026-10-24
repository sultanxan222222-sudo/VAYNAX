FROM node:20-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY src ./src
ENV NODE_ENV=production
ENV PORT=8080
RUN mkdir -p /data && chown -R node:node /app /data
USER node
ENV DB_PATH=/data/vaynax.sqlite
EXPOSE 8080
CMD ["npm", "start"]
