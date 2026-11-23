#!/usr/bin/env bash
set -euo pipefail
umask 077
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../.." && pwd)
cd "$repo_dir"
r2_java="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
r2_javac="${JAVA_HOME:+${JAVA_HOME}/bin/}javac"
# Never source a project/server .env. Credentials must be scoped to the operator's tool process.
r2_classpath=$(./gradlew --quiet printR2MigrationClasspath)
r2_classes="$repo_dir/build/r2-migration"
mkdir -p "$r2_classes"
"$r2_javac" --release 21 -cp "$r2_classpath" -d "$r2_classes" \
  "$script_dir/R2MigrationCore.java" "$script_dir/R2ImageMigration.java"
if [[ "${1:-}" == "test" ]]; then
  "$r2_javac" --release 21 -cp "$r2_classpath:$r2_classes" -d "$r2_classes" "$script_dir/"*Test.java
  "$r2_java" -cp "$r2_classpath:$r2_classes" R2MigrationCoreTest
  "$r2_java" -cp "$r2_classpath:$r2_classes" R2MigrationPlanTest
  if [[ -f "$r2_classes/R2MigrationWireTest.class" ]]; then
    "$r2_java" -cp "$r2_classpath:$r2_classes" R2MigrationWireTest
  fi
else
  exec "$r2_java" -cp "$r2_classpath:$r2_classes" R2ImageMigration "$@"
fi
