#!/bin/sh

wget https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.7.14/rabbitmq-server-generic-unix-3.7.14.tar.xz
tar xf rabbitmq-server-generic-unix-3.7.14.tar.xz
mv rabbitmq_server-3.7.14 rabbitmq

rabbitmq/sbin/rabbitmq-server -detached

sleep 3

true
