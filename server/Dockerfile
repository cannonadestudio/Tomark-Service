FROM php:8.3-apache
RUN a2enmod headers rewrite \
 && sed -ri 's!/var/www/html!/var/www/public!g' /etc/apache2/sites-available/000-default.conf \
 && printf '<Directory /var/www/public>\n  AllowOverride All\n  Require all granted\n</Directory>\n' > /etc/apache2/conf-enabled/aerohangar.conf
COPY public/ /var/www/public/
COPY private/ /var/www/private/
RUN mkdir -p /var/www/private/state && chown -R www-data:www-data /var/www/public/data /var/www/private/state
# Daily TOMARK check is run by the host cron or the "cron" service in docker-compose.yml
EXPOSE 80
