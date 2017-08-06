/*
 * Copyright 2017 Alexander Orlov <alexander.orlov@loxal.net>. All rights reserved.
 */

"use strict";

const navTo = async function (hash) {
    location.hash = hash;
    const handlerMap = {
        "": "/whois.html",
        "#whois": "/whois.html",
        "#imprint": "/imprint.html"
    };
    if (!handlerMap[location.hash]) {
        document.getElementById("main").innerHTML = '<div class="info warn">Page Not Found</div>';
    } else {
        const xhr = new XMLHttpRequest();
        xhr.open("GET", handlerMap[location.hash]);
        xhr.onload = function () {
            document.getElementById("main").innerHTML = this.responseText;
        };
        xhr.send();
    }
};

const applySiteProperties = async function () {
    const xhr = new XMLHttpRequest();
    xhr.open("GET", "/properties.json");
    xhr.onload = function () {
        const siteProperties = JSON.parse(this.responseText);
        document.getElementById('signature').innerHTML = siteProperties['year'] + ' ' + siteProperties['copyright'];
        document.getElementById('header-description').innerHTML = siteProperties['titleDesc'];
    };
    xhr.send();
};

const callWhois = async function () {
    let query;
    if (location.hostname === "localhost") {
        query = "?queryIP=185.17.205.98";
    } else {
        query = "";
    }
    const xhr = new XMLHttpRequest();
    xhr.open("GET", "/whois/city" + query);
    xhr.onload = function () {
        console.dirxml(this.responseText);
        let whoisInfo = JSON.parse(this.response);

        function process(dlE, idx, key, value) {
            let dtE = document.createElement("dt");
            let ddE = document.createElement("dd");
            dtE.textContent = key;
            console.info(value);
            console.info(typeof(value));
            ddE.textContent = value;
            dlE.appendChild(dtE);
            dlE.appendChild(ddE);

            return ddE;
        }

        function traverse(dlE, obj, process) {
            Object.keys(obj).forEach(function (key, idx) {
                let parentDdE = process.apply(this, [dlE, idx, key, obj[key]]);
                if (obj[key] !== null && typeof(obj[key]) === "object") {
                    let dlE = document.createElement("dl");
                    parentDdE.appendChild(dlE);
                    traverse(dlE, obj[key], process);
                }
            })
        }

        const dlWhoisContainer = document.createElement("dl");

        traverse(dlWhoisContainer, whoisInfo, process);
        document.getElementById("main").insertBefore(dlWhoisContainer, document.getElementById("whoisContainer"));
    };
    xhr.send();
};

console.log("%c%s", "color: hsla(222, 99%, 44%, .9); background: #eef; font-size: 2em; font-weight: bold; border-radius: 1em;", " Don't Panic😊");
