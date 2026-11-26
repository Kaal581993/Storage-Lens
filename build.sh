#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$project_dir"
mkdir -p target/native-resources/native/linux-x86_64 target/native
./scripts/review.sh run gcc -std=c11 -O2 -Wall -Wextra -Werror native/nvme_health.c -o target/native/storage-lens-nvme
./scripts/review.sh run gcc -std=c11 -O2 -Wall -Wextra -Werror -DSTORAGE_LENS_NVME_TEST native/nvme_health.c -o target/native/nvme-health-test
./scripts/review.sh binary target/native/storage-lens-nvme target/native/nvme-health-test
./scripts/review.sh run target/native/nvme-health-test --self-test
jdk_dir="$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")"
./scripts/review.sh run gcc -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
  -I "$jdk_dir/include" -I "$jdk_dir/include/linux" native/storage_stat.c \
  -o target/native-resources/native/linux-x86_64/libstorage_lens.so
./scripts/review.sh binary target/native-resources/native/linux-x86_64/libstorage_lens.so
./scripts/review.sh run mvn --offline --batch-mode --no-transfer-progress verify
./scripts/review.sh binary target/storage-lens.jar
