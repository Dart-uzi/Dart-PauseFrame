#!/usr/bin/env sh
exec java "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/gradle-bootstrap.java" "$@"
