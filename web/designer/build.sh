#!/bin/sh
# Wraps the designer page (designer.html, the file published as the claude.ai artifact) into a
# standalone document (index.html) that opens directly in a browser.
set -e
cd "$(dirname "$0")"
{
  printf '<!doctype html>\n<html lang="de">\n<head>\n<meta charset="utf-8">\n'
  printf '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n</head>\n<body>\n'
  cat designer.html
  printf '\n</body>\n</html>\n'
} > index.html
echo "wrote $(pwd)/index.html"
