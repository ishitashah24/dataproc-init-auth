#!/usr/bin/env bash

# Use this script to obtain a token
export AUDIENCE="https://secureinit.local"
curl -H "Metadata-Flavor: Google" "http://metadata/computeMetadata/v1/instance/service-accounts/default/identity?audience=${AUDIENCE}&format=full" 2>/dev/null



