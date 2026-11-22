# 단일 서버 개발·운영 배포

계약은 [환경 분리 Spec](../docs/specs/environments.md), 관련 이슈는
[#212](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/212)다.
이 안내는 **적용 절차**이며 서버 적용 완료 기록이 아니다. 실제 변경은 조정 채팅에서 순서대로 수행한다.

## 배치와 배포 조건

| 구분 | 개발 | 운영 | 공용 모니터링 |
| --- | --- | --- | --- |
| 서버 경로 | `/home/bunch/dev` | `/home/bunch/prod` | `/home/bunch/monitoring` |
| Compose 프로젝트 | 기존 `bunch` | `bunch-prod` | 기존 `bunch` |
| 설정 | `deploy/compose.yaml` | 동일 파일 | `deploy/monitoring/compose.yaml` |
| 환경 예제 | `deploy/.env.example` | `deploy/environments/.env.example` | `deploy/monitoring/.env.example` |
| API / WebSocket | `dev.bunch.kr` | `api.bunch.kr` | 관리자 hostname |
| 자동 배포 | `dev` | `main` | `main` 변경만, 별도 활성화 |

2026-11-22 조정 채팅의 읽기 전용 확인: 현재 `/home/bunch/compose.yaml`, `/home/bunch/.env`,
Compose 프로젝트 `bunch`, 개발 데이터 볼륨 `bunch-pgdata`, `bunch-redis-data`를 사용 중이다.
전체 RAM 7940MiB / available 5822MiB, 디스크 35GiB 여유는 당시 한 시점 측정값이다.
실제 네트워크·모니터링 볼륨·이미지 digest·bind mount는 적용 직전에 다시 조사한다.

**기존 홈의 DB·Redis·볼륨·프로젝트는 이름을 바꾸지 않는다.** 개발 데이터를 운영에 복사하지 않는다.
모든 데이터 볼륨과 네트워크는 `external: true`다. 이름이 틀리거나 없으면 기동을 중단하며 자동으로 빈 개발 볼륨을 만들지 않는다.
개발 기본 네트워크 `bunch_default`, 운영 `bunch-prod-default`, 관측 `bunch-dev-metrics` / `bunch-prod-metrics`를 분리한다.
앱만 자기 관측 네트워크에 연결하고 공용 Prometheus가 두 관측 네트워크를 수집한다.
공용 관측 기본 네트워크 `bunch-monitoring`은 개발 DB·Redis 네트워크와 다르다.
같은 프로젝트의 분할 Compose이므로 **`--remove-orphans`, `down`, `down -v`, prune을 사용하지 않는다.**

## 1. 자동 배포부터 중지

새 코드를 어느 배포 브랜치에 병합하기 **전** GitHub Actions의 기존 CI workflow를 Disable하고,
진행 중인 `main → ~` 배포가 모두 끝났거나 취소됐는지 확인한다. 과거 실행의 재실행도 금지한다.
GitHub의 repository variables `DEPLOY_DEV_ENABLED`, `DEPLOY_PROD_ENABLED`, `MONITORING_DEPLOY_ENABLED`는
미설정 또는 `false`로 둔다. **이 변수는 과거 workflow에 소급 적용되지 않으므로 Disable을 대체하지 않는다.**
`CONTRIBUTING.md`와 기존 모니터링 Spec의 `dev 검증만/main 홈 배포/latest` 설명은 이번 전환 후 이 안내가 대체한다.

GitHub Environments `dev`, `prod`를 준비하고 각각 배포 브랜치를 `dev`, `main`으로 제한한다.
각 Environment에 `SSH_HOST`, `SSH_USER=bunch`, `SSH_PASSWORD`를 등록한다. `SSH_PORT`는 필요할 때만 variable로 지정한다.
GHCR은 workflow의 `GITHUB_TOKEN`만 사용한다. 서버 `.env`나 PAT로 대체하지 않는다.
새 workflow도 준비 표식이 맞지 않으면 **파일 전송 전 실패**한다. 표식은 마지막 검증 후 사람이 만든다.
SSH는 기존 방식의 `accept-new`다. 고정 host key 확인은 현재 구현 범위에 포함하지 않는다.

중단 지점 A: 이 단계까지 기존 서버 요청·데이터는 계속 유지된다. 새 자동 배포는 비활성 상태다.

## 2. 변경 없는 현황 검사와 백업

검토한 `deploy/` 파일만 서버의 별도 준비 디렉터리(예: `~/issue-212/deploy`)로 전송한다.
`.env`, 루트의 실제 환경 파일, 로컬 build 결과, Git 작업 디렉터리를 함께 보내지 않는다.
서버에서 `bunch`로 실행한다. Docker Compose v2(`--wait` 지원), Python3, `flock`과 호스트에서 컨테이너 관리 포트로의 접근이 필요하다.

```bash
python3 ~/issue-212/deploy/prepare-environments.py
```

기본 실행은 Docker의 라벨·볼륨·네트워크·이미지 digest·bind mount만 읽고 **파일·컨테이너를 변경하지 않는다.**
전체 `docker inspect`, 환경 덤프, trace, 값이 출력되는 `docker compose config`는 사용하지 않는다.
검사 실패 시 예상과 다른 현황을 확인하고 중단한다. 임의 이름으로 계속하지 않는다.
적용 시 생성되는 `~/environment-inventory.json`에는 위 허용 목록만 들어가며 비밀값은 없다.

백업은 접근이 제한된 별도 디렉터리와 서버 밖 복사본에 둔다. 개발 앱 쓰기를 잠깐 중지한 일관된 시점에
DB dump와 Redis 전체 볼륨(AOF 포함)을 확보한다. 아래는 기존 설정으로 수행하는 서버 명령이다.
데이터 크기에 따라 이 백업 구간은 몇 분 이상 걸릴 수 있으므로 점검 시간을 확보한다.

```bash
backup="$HOME/backups/issue-212-$(date +%Y%m%d-%H%M%S)"
(
set -euo pipefail
umask 077
mkdir -p "$backup"
cd /home/bunch
docker compose --env-file .env -f compose.yaml stop app cloudflared
docker compose --env-file .env -f compose.yaml exec -T postgres \
  sh -c 'exec pg_dump -U "$POSTGRES_USER" -d bunch -Fc' > "$backup/postgres.dump"
docker compose --env-file .env -f compose.yaml exec -T redis redis-cli SAVE
docker compose --env-file .env -f compose.yaml stop redis
# Obtain a pinned digest without printing environment variables.
redis_id=$(docker compose --env-file .env -f compose.yaml ps -a -q redis)
redis_image=$(docker inspect --format '{{.Image}}' "$redis_id")
docker run --rm --network none --entrypoint tar \
  --mount type=volume,source=bunch-redis-data,target=/data,readonly \
  --mount "type=bind,source=$backup,target=/backup" "$redis_image" \
  -czf /backup/redis-backup.tar -C /data .
cp compose.yaml "$backup/legacy-compose.yaml"
cp -R monitoring "$backup/legacy-monitoring"
docker compose --env-file .env -f compose.yaml up -d --no-deps --wait redis
docker compose --env-file .env -f compose.yaml up -d --no-deps app cloudflared
)
```

위 블록은 어느 명령이든 실패하면 즉시 중단한다. 실행 결과가 실패라면 앱을 계속 중지한 채 원인과 백업 상태를 확인한다. **실패한 dump/아카이브를 정상 백업으로 취급하지 않는다.**
모니터링 DB/로그 기록도 보존하려면 해당 네 서비스를 잠시 중지한 뒤, 실제 조사한 각 볼륨을 Redis와 같은
읽기 전용 tar 방식으로 백업하고 기존 설정으로 다시 기동한다. 정확한 볼륨 이름을 조사해 지정한다.
기존 `~/.env`는 이 스크립트로 복사하거나 출력하지 않는다. 별도 비밀값 관리 경로로 복구 가능성을 확인한다.

서버와 분리된 **임시 DB/Redis**에 dump/아카이브를 복원해 Flyway 이력·핵심 행 수·Redis 키/TTL을 검사한다.
운영 DB나 현재 개발 데이터에 복원 시험하지 않는다. 모니터링 백업도 복원 여부를 확인한다.
서버 밖 백업 복사와 복구 시험이 성공한 후에만 다음 표식을 만든다.

```bash
touch "$backup/restore-tested"
python3 ~/issue-212/deploy/prepare-environments.py --apply --backup "$backup"
```

`--apply`도 컨테이너·볼륨·네트워크를 만들거나 재시작하지 않는다. 기존 홈 파일을 보존하고
새 Compose·validator·예제만 준비하며 기존 대상 파일이 있으면 덮어쓰지 않고 실패한다.
중간 실패 시 준비 파일을 확인해 개별 복구한다. 무조건 재실행하거나 디렉터리 전체를 지우지 않는다.

## 3. 환경 파일과 외부 네트워크 준비

서버에서 예제를 **각 경로에 새로** 준비하고 비밀값 관리 도구로 값을 입력한다.
기존 루트 `.env`를 복사하지 않는다. 기존 개발 DB 암호·JWT·기능 플래그·APNs·제공자 설정은 기존 값과 일치하도록
개별 확인하고 운영에는 독립 키를 발급한다. `.env`는 600, 디렉터리는 700 권한을 유지한다.

```bash
# Files must not already exist; noclobber prevents accidental overwrite.
(set -C; cat ~/dev/.env.example > ~/dev/.env)
(set -C; cat ~/prod/.env.example > ~/prod/.env)
(set -C; cat ~/monitoring/.env.example > ~/monitoring/.env)
chmod 600 ~/dev/.env ~/prod/.env ~/monitoring/.env
docker network create bunch-dev-metrics
docker network create bunch-prod-metrics
docker network create bunch-prod-default
docker network create bunch-monitoring
```

기존 `bunch_default`는 그대로 둔다. 이미 존재하는 신규 네트워크가 있으면 inspect로 소유·연결 상태를 확인하고
이름이 같다는 이유로 재사용하지 않는다. `environment-inventory.json`의 이미지 digest를 개발 환경에 적용한다.
기존 PostgreSQL/Redis 이미지 버전은 이 작업으로 올리지 않는다. 예제의 all-zero digest는 실행 불가 placeholder다.
<a id="profanity-dictionary"></a>

외부 금칙어 사전은 기존 bind 경로를 `PROFANITY_DICTIONARY_MOUNT`에 그대로 지정한다.
준비 스크립트는 참조용 `~/dev/.legacy-profanity` 링크도 만들며 사전을 이동·삭제하지 않는다.

공용 관측 환경의 네 볼륨 변수에는 inventory에 나온 **기존 볼륨 이름**을 넣는다.
Compose 프로젝트는 계속 `bunch`다. `bunch-monitoring` 같은 새 프로젝트명으로 바꾸어 데이터 볼륨을 만들지 않는다.
앱 환경에는 Grafana/Discord 키를 넣지 않으며 관측 환경에는 DB/JWT/R2 키를 넣지 않는다.

현재 앱 기능을 잃지 않도록 `RECO_COMBINATION_ENABLED`, `BUNCH_BUNDLE_STAGE_THRESHOLDS`, APNs·암호화 키도
실제 값을 확인한다. 새 예제 기본값이 기존 설정을 대체한다고 가정하지 않는다.
개발·운영 모두 앱/DB/Redis 호스트 포트를 게시하지 않는다. Tunnel의 원본 서비스는 `http://app:8080`이다.
운영/개발의 Tunnel ID와 토큰은 다르다. 관리자 Grafana는 공용 관측의 별도 Tunnel 토큰으로 `http://grafana:3000`에 연결한다.

### 자원 상한

| 구분 | 앱 | PostgreSQL | Redis | Tunnel | 합계 |
| --- | --- | --- | --- | --- | --- |
| 개발 CPU / MiB | 0.50 / 1024 | 0.25 / 512 | 0.10 / 192 | 0.05 / 128 | 0.90 / 1856 |
| 운영 CPU / MiB | 1.00 / 2048 | 0.60 / 1024 | 0.20 / 256 | 0.05 / 128 | 1.85 / 3456 |

공용 관측은 최대 0.8CPU/1152MiB 이내다. 전체 상한은 최대 3.55CPU/6464MiB로,
실측 총 RAM 7940MiB에서 최소 약1476MiB와 0.45CPU를 OS·Docker 여유로 남긴다.
상한은 예약이 아니며 CPU 경쟁·메모리 OOM·디스크 부족은 별도 관측한다. JVM 최대 힙은 컨테이너 메모리의 60%다.
실측 후 조정하되 두 앱+저장소+관측+OS의 합계를 다시 계산한다. validator가 예산 초과를 거부한다.

## 4. 개발 경로 이전과 공용 관측

새 개발 환경의 변수·이미지·볼륨·network를 먼저 검증한다. 초기 검사는 준비 표식을 요구하지 않는다.

```bash
(
set -euo pipefail
python3 ~/dev/environment_check.py dev ~/dev --resources
cd ~/dev
docker compose --env-file .env -f compose.yaml config --quiet
# Current DB/Redis containers keep running. Replace only app; brief API/WebSocket interruption.
docker compose --env-file .env -f compose.yaml up -d --no-deps app
)
```

앱 재생성에서 연결·진행 중 WebSocket이 잠시 끊기며 재연결·재인증을 검증한다.
DB/Redis는 이 단계에서 재시작하지 않는다. 현재 자원 상한은 새 앱에만 즉시 적용된다.
DB/Redis 상한은 쓰기 중단/백업 준비 후 별도 짧은 점검 구간에 **동일 이미지·동일 볼륨**으로 각 서비스만 재생성해야 적용된다.
이 변경 전까지는 전체 상한이 적용됐다고 보고하지 않는다. 실제 현재 메모리 사용량을 확인하고 같은 점검 창에서 다음을 수행한다.
어느 명령이든 실패하면 앱/Tunnel을 중지한 상태로 원인을 확인한다.

```bash
(
set -euo pipefail
python3 ~/dev/environment_check.py dev ~/dev --resources
cd ~/dev
docker compose --env-file .env -f compose.yaml stop app cloudflared
docker compose --env-file .env -f compose.yaml up -d --no-deps --force-recreate --wait postgres
docker compose --env-file .env -f compose.yaml up -d --no-deps --force-recreate --wait redis
docker compose --env-file .env -f compose.yaml up -d --no-deps app cloudflared
)
```

관측 설정을 바꾸기 전 기존 네 서비스만 잠시 중지한다.
기존 프로젝트가 같으므로 `-f`와 서비스 이름을 매번 지정하고 다른 서비스를 orphan으로 제거하지 않는다.

```bash
(
set -euo pipefail
: "${backup:?Set the verified backup directory from step 2}"
test -f "$backup/restore-tested"
python3 ~/issue-212/deploy/monitoring/config_test.py --env-file ~/monitoring/.env
cd /home/bunch
docker compose --env-file .env -f compose.yaml stop alloy loki prometheus grafana
cp ~/issue-212/deploy/monitoring/config.alloy ~/monitoring/config.alloy
cp ~/issue-212/deploy/monitoring/loki.yaml ~/monitoring/loki.yaml
cp ~/issue-212/deploy/monitoring/prometheus.yaml ~/monitoring/prometheus.yaml
# Legacy grafana tree is backed up above. Replace only this reviewed provisioning tree.
mv ~/monitoring/grafana "$backup/pre-split-grafana"
cp -R ~/issue-212/deploy/monitoring/grafana ~/monitoring/grafana
chmod -R u=rwX,go=rX ~/monitoring/grafana
cp ~/issue-212/deploy/monitoring/config_test.py ~/monitoring/config_test.py
cd ~/monitoring
docker compose --env-file .env -f compose.yaml config --quiet
docker compose --env-file .env -f compose.yaml up -d --no-deps --force-recreate alloy loki prometheus grafana
# Start the distinct monitoring Tunnel after its administrator hostname is configured.
docker compose --env-file .env -f compose.yaml up -d --no-deps monitoring-cloudflared
)
```

모니터링 수집·알림은 이 구간 잠시 중단한다. 기존 네 데이터 볼륨이 유지되는지 확인한다.
운영 앱이 아직 없을 때 prod 수집 중단 알림이 예상되므로 첫 전환은 시험 webhook 또는 Grafana silence로 진행한다.
운영 준비 후 silence를 해제한다. Loki/Grafana에 두 환경 라벨, Prometheus에 `dev-app:8081` / `prod-app:8081`를 확인한다.
공용 관리자 URL은 별도 Tunnel이며 API/관리 포트/Loki/Prometheus를 공개하지 않는다.
공용 관측 자세한 변수·검증은 [모니터링 안내](monitoring/README.md)를 따른다.

중단 지점 B: 개발 앱과 기존 데이터가 `~/dev`에서 정상이고 기존 `api.bunch.kr`은 계속 개발을 가리킬 수 있다.
아직 자동 배포·운영 도메인 전환을 하지 않는다.

## 5. 개발 hostname → 앱 전환 → R2 순서

1. 개발 Tunnel에 `dev.bunch.kr → http://app:8080`을 연결한다. 기존 `api` 연결은 유지한다.
2. 실제 테스트 앱의 **API 주소와 WebSocket 주소를 모두** dev hostname으로 변경한다.
3. 로그인/갱신·사진 조회/업로드/삭제·초대·STOMP 연결/재연결을 실제 iOS/WebView로 검증한다.
   실제 프런트 Origin을 `BUNCH_WS_ALLOWED_ORIGINS`에 허용한다. API 주소를 무조건 Origin으로 쓰지 않는다.
4. #213 이미지를 개발에 배포하고 `R2_ENDPOINT`(버킷명 없는 account origin), `R2_BUCKET=bunch-dev`,
   개발 전용 `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`를 설정한다. 서명 기본 만료는 코드의 1800초다.
5. #213 일회성 이전 도구로 이전 자료·키·응답 계약을 검증한다. Cloudinary 키 제거는 **코드 배포와 데이터 이전 검증 후**다.
   기존 Cloudinary 앱으로 되돌릴 가능성이 남아 있으면 해당 설정·데이터를 보존한다.

중단 지점 C: 테스트 앱이 dev API/WebSocket과 개발 R2를 실제 사용한다는 근거가 확보돼야 다음으로 간다.
이를 확인하지 않고 현재 `api.bunch.kr`을 운영으로 돌리면 기존 테스트 앱이 빈 운영 DB에 접속한다.

## 6. 빈 운영 DB와 독립 서비스 준비

운영 JWT·DB·R2(bucket=`bunch-prod`)·Tunnel 키를 먼저 독립 발급하고 `.env`에 확정한다.
운영 이미지 digest도 고정한다. 값이 바뀌면 앱·Tunnel 최초 기동 직전에 다시 검사한다.
먼저 새 이름의 볼륨이 **존재하지 않는지** 검사한다. 이미 있으면 생성 명령을 실행하지 말고 현황을 확인한다.

```bash
(
set -euo pipefail
# A successful listing and an empty exact-name match are BOTH required.
existing=$(docker volume ls --format '{{.Name}}' | awk '$0 == "bunch-prod-pgdata" || $0 == "bunch-prod-redis-data"')
test -z "$existing"
# Create empty volumes only after that guard succeeds:
docker volume create --label kr.bunch.environment=prod bunch-prod-pgdata
docker volume create --label kr.bunch.environment=prod bunch-prod-redis-data
python3 ~/prod/environment_check.py prod ~/prod --resources
cd ~/prod
docker compose --env-file .env -f compose.yaml up -d --wait postgres redis
# Enter the NEW production administrator password at the prompt; never put it in shell history.
docker compose --env-file .env -f compose.yaml exec postgres psql -h 127.0.0.1 -U postgres -d postgres -W
)
```

psql에서 신규 운영 앱 계정과 빈 DB를 만든다. 개발 DB를 dump/restore하지 않는다.

```sql
\set ON_ERROR_STOP on
CREATE USER bunch;
\password bunch
CREATE DATABASE bunch OWNER bunch;
\connect bunch
GRANT USAGE, CREATE ON SCHEMA public TO bunch;
\quit
```

`\password` 값은 운영 `.env`의 `DB_PASSWORD`와 같아야 한다. 기존 볼륨에서 환경 변수 변경은 DB 암호 변경이 아니다.
앱 최초 기동 시 Flyway 이력과 `ddl-auto: validate` 성공을 확인한다.

```bash
(
set -euo pipefail
# Recheck the FINAL credentials immediately before creating either connector/application.
python3 ~/prod/environment_check.py prod ~/prod --resources
cd ~/prod
docker compose --env-file .env -f compose.yaml up -d --no-deps app
# Configure a distinct production Tunnel; switch api hostname ONLY after step 5 evidence.
docker compose --env-file .env -f compose.yaml up -d --no-deps cloudflared
)
```

공개 전 관리 health와 내부 API 응답·빈 DB·이미지/환경 라벨·볼륨 격리를 확인하고 `api.bunch.kr`을 운영 Tunnel로 전환한다.
도메인 전환 중 기존 연결은 재연결이 필요하며 DNS/Tunnel 전파 지연은 별도 확인한다.
prod의 임시 인증 API(`/api/v1/auth/dev/token`)는 기존 앱 코드에서 열려 있으므로 정식 운영 공개 전 별도 앱 작업으로 차단해야 한다.
이 작업은 `src/**` 소유 범위 밖이므로 코드를 수정하지 않는다.

## 7. 자동 배포 활성화와 일상 배포

개발 R2/도메인·운영 격리·관측 검증을 마친 환경에만 표식을 만든다.
validator는 표식·R2 준비를 검사한다. 키 중복은 준비 표식과 무관하게 일반 검사부터 대조하며,
개발 환경 파일이 없으면 운영 최초 기동 검사를 거부한다. JWT/DB/R2/Tunnel 키가 같으면 실패한다.

```bash
printf '%s\n' 'bunch-environment-v1:dev' > ~/dev/.deployment-ready
printf '%s\n' 'bunch-environment-v1:prod' > ~/prod/.deployment-ready
printf '%s\n' 'bunch-monitoring-v1' > ~/monitoring/.deployment-ready
python3 ~/dev/environment_check.py dev ~/dev --ready --resources
python3 ~/prod/environment_check.py prod ~/prod --ready --resources
```

GitHub repository variables `DEPLOY_DEV_ENABLED=true`, `DEPLOY_PROD_ENABLED=true`를 각각 설정한다.
공용 모니터링 검증 후 repository variable `MONITORING_DEPLOY_ENABLED=true`도 설정한다.
활성화 변수는 job 시작 전 판단하므로 Environment scope에만 두면 안 된다. SSH 비밀값은 Environment별로 둔다.
새 workflow가 dev와 main 모두에 반영됐는지 확인하고 CI workflow를 다시 Enable한다.
처음에는 검증된 커밋의 push/재실행을 환경별로 하나씩 진행하고 실제 health·메트릭·로그를 확인한다.
과거 홈 배포 workflow 실행을 재실행하지 않는다.

새 CI는 PR과 dev/main push에서 변경을 감지한다. 문서만 변경하면 `build`는 skipped다.
앱·배포 구성 변경은 실제 DB/Redis 전체 build, 배포 검사, 관측 검증을 실행한다.
검증 성공 후 앱 변경 시만 `dev-<SHA>` / `prod-<SHA>`를 게시하고 서버에서 digest로 해석한다.
두 환경의 배포 job은 공통 concurrency group으로 직렬화하고 `queue: max`로 최대 100개 대기 작업을 유지한다.
100개를 넘는 추가 대기는 GitHub가 취소하므로 해당 상황은 실행 이력을 확인해 재배포한다.
후보 설정은 `.incoming-<SHA>`에서 검증한 뒤 반영한다. `.env`는 전송하거나 덮어쓰지 않는다.
앱만 `--no-deps`로 재생성하고 실제 관리 health를 확인한 뒤 환경별 `.release.env`에 digest를 기록한다.
CI는 PostgreSQL·Redis·Tunnel을 재시작하지 않는다. 모니터링만 바뀌면 네 관측 서비스만 갱신하며 dev에서는 공용 관측을 배포하지 않는다.

앱 배포에는 단일 컨테이너 교체와 health 대기 구간의 짧은 중단이 있다. 무중단 배포를 보장하지 않는다.
일상 수동 Compose 명령은 CI가 기록한 release 파일을 함께 사용한다.

```bash
cd ~/dev  # or ~/prod
# After the first CI release, include both files in every manual app command.
docker compose --env-file .env --env-file .release.env -f compose.yaml config --quiet
```

## 8. 실패와 복구

먼저 자동 배포 변수를 끄고 준비 표식을 별도 이름으로 옮겨 다음 배포를 차단한다.
루트 원본 Compose·환경 파일·개발 데이터·백업은 전체 전환 검증이 끝날 때까지 보존한다.

- 준비 단계 실패: 서비스는 변경되지 않았다. 새 준비 파일만 조사하고 원본 홈으로 계속 운영한다.
- 개발 앱 이전 실패: 원본 `/home/bunch/compose.yaml`로 `app`만 재생성한다. 기존 DB/Redis에는 손대지 않는다.
  기존 이미지는 mutable `latest`를 다시 pull하지 말고 inventory의 이전 digest를 `APP_IMAGE`로 지정한다.
- 관측 실패: 네 관측 서비스만 중지하고 백업한 `legacy-monitoring` 설정과 이전 Compose로 복원 후 네 서비스만 재생성한다.
  외부 볼륨 이름을 유지하며 백업의 `.env`나 준비 파일로 서버 secret을 덮어쓰지 않는다.
  새 알림 UID 4개의 정리 절차도 [모니터링 복구 안내](monitoring/README.md)를 따른다.
- 운영 도메인 전환 실패: 새 운영 앱에 쓰기가 생겼다면 먼저 쓰기를 중단하고 분기 데이터를 확인한다.
  단순 `api → 개발` 복귀는 운영 쓰기를 개발 DB로 옮기지 않으며 데이터 손실/계정 혼선을 만들 수 있다.
  전환 전 단계로 복구할 수 있는지 조정 채팅에서 판단한다. dev hostname은 계속 개발로 유지한다.
- CI 앱 교체 실패: 실패한 새 컨테이너가 남을 수 있다. 해당 배포의 백업 경로와 이전 digest를 확인해 이전 설정부터 복원·검증한 다음 앱만 복원한다.
  원인은 별도로 해결한 후 다시 검증·배포한다. 자동 DB rollback은 없다.

```bash
set -euo pipefail
cd ~/dev  # or ~/prod; choose the affected environment only
: "${RELEASE_BACKUP:?Set the affected release backup directory}"
: "${PREVIOUS_DIGEST:?Set the recorded previous known-good digest}"
# Restore the previous configuration FIRST; preserve the live .env.
install -m 600 "$RELEASE_BACKUP/compose.yaml" compose.yaml
if test -f "$RELEASE_BACKUP/.release.env"; then
  install -m 600 "$RELEASE_BACKUP/.release.env" .release.env
else
  (umask 077; printf 'APP_IMAGE=%s\n' "$PREVIOUS_DIGEST" > .release.env)
fi
APP_IMAGE="$PREVIOUS_DIGEST" docker compose --env-file .env --env-file .release.env -f compose.yaml config --quiet
APP_IMAGE="$PREVIOUS_DIGEST" docker compose --env-file .env --env-file .release.env -f compose.yaml up -d --no-deps app
# Verify health, DB/Flyway compatibility, metrics and WebSocket reconnect before enabling CI again.
```

**Flyway DDL 변경은 이전 앱 digest로 되돌려도 복구되지 않는다.** 하위 호환성을 먼저 확인하고,
필요하면 쓰기를 중단한 뒤 별도 복구 시험을 마친 DB 백업을 복원한다. 개발/운영을 혼동하지 않는다.
`docker compose down -v`, `docker volume rm`, 기존 데이터 경로 삭제는 전환·복구 절차에 없다.

## 로컬 검증

실제 `.env` 없이 예제와 임시 Docker 자원만 사용한다. 운영 Discord·DB·자격 증명은 사용하지 않는다.

```bash
bash deploy/check.sh
python3 -m unittest discover -s deploy/tests -v
bash deploy/monitoring/check.sh
python3 deploy/monitoring/smoke_test.py
# Use this task's dedicated local database/Redis ports, never the production database.
DB_URL=jdbc:postgresql://localhost:55212/bunch_test REDIS_PORT=56212 ./gradlew clean build
```

모니터링 smoke는 실제 Grafana/Loki/Prometheus와 로컬 HTTP stub를 쓰며 실제 Discord/iOS SDK 검증과 다르다.
프로젝트에는 별도 Kotlin lint/정적 분석 도구가 없다. shell/YAML/Compose·실행 검증과 전체 build 결과를 구분해 보고한다.

참고: [Compose external 볼륨](https://docs.docker.com/reference/compose-file/volumes/),
[Compose 환경 변수 우선순위](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/),
[GitHub 배포 Environment](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
