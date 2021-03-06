#!/usr/bin/env powershell

#https://certbot.eff.org
#cf. https://gist.github.com/cecilemuller/a26737699a7e70a7093d4dc115915de8
#improve SSL Labs Server Test rating: https://www.digitalocean.com/community/tutorials/how-to-secure-nginx-with-let-s-encrypt-on-ubuntu-14-04

sudo apt install software-properties-common
sudo add-apt-repository ppa:certbot/certbot
sudo apt update
sudo apt install certbot

docker stop router
sudo certbot --standalone certonly --expand `
    -d sky.loxal.net `
    -d muctool.loxal.net `
    -d www.loxal.net `
    -d ci.loxal.net `
    -d api.loxal.net `
    -d loxal.net `
    -d muctool.de `
    -d www.muctool.de `
    -d whois.muctool.de `
    -d api.muctool.de `
    -d doc.muctool.de

docker start router
