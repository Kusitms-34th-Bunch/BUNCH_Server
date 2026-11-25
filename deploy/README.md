# 개발·운영 분리와 배포

[환경 계약](../docs/specs/environments.md)에 따라 기존 테스트 데이터는 개발로 옮기고
`api.bunch.kr`은 새 빈 운영 환경에서 사용한다. GitHub Actions는 빌드·검증·이미지 게시를 수행한다.

## 1. 현재 앱을 유지하면서 준비

- `dev → main` 릴리스 PR에 새 workflow가 포함됐는지 확인하고 기존 홈 배포가 실행 중이면 종료를 기다린다.
  과거 홈 배포 실행을 다시 실행하지 않는다. 새 `main` push는 검증한 앱 이미지를 GHCR에 게시하고 서버에서 받는다.
  서버에서 이미지를 받을 때 기존 SSH Secrets와 Actions의 `GITHUB_TOKEN`을 사용한다.
- #226을 먼저 반영하고 #225를 함께 적용한다. R2는 현재 V53에서 V55로 넘어간다.
  #217을 나중에 반영할 경우 아직 적용하지 않은 V54의 번호를 V55 이후로 조정한다.
  두 `.env`의 `APP_IMAGE`에는 CI 요약의 같은 GHCR digest를 입력한다.
- 현재 홈 Compose·환경 파일·실행 이미지 digest·마운트·볼륨을 확인하고 복구할 수 있게 보관한다.
  DB dump와 Redis 볼륨(AOF 포함)은 별도 저장소에 보관하고 별도 DB/Redis에서 복구를 시험한다.
  기존 Redis의 일관된 전체 볼륨 백업은 쓰기를 멈추고 Redis를 정지한 시점에 확보한다.
- 기존 사진은 [#226의 R2 이전 안내](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/pull/226/files)에 따라 별도 관리 작업으로
  `bunch-dev`에 복사한다. 기존 볼륨과 개발 DNS 연결은 운영 전환 이후에 준비할 수 있다.

## 2. 서버 설정 준비

서버의 기존 Compose를 `~/dev/compose.yaml`, `~/prod/compose.yaml`의 출발점으로 사용한다.
실제 운영 파일과 두 환경의 `.env`는 서버에서 관리한다.

| 경로 | Compose 변경 | `.env` 설정 |
| --- | --- | --- |
| `~/dev` | `name: bunch-dev`, 앱·DB·Redis·Tunnel만 사용, 기존 DB·Redis 외부 볼륨 | 기존 DB·JWT·푸시 암호화 키, 개발 R2, 개발 주소와 Tunnel |
| `~/prod` | `name: bunch`, 새 운영 DB·Redis 외부 볼륨, 기존 모니터링 유지 | 새 DB·JWT와 기존 푸시 암호화 키, 운영 R2, 기존 Tunnel·Grafana·Discord |

PostgreSQL은 `postgres:16`, Redis는 `redis:7`을 사용한다. Compose 기본 네트워크 이름은
개발 `bunch-dev_default`, 운영 `bunch_default`다. 앱 설정은 `env_file: .env`로 읽고
`APP_IMAGE`도 기존대로 `.env`에서 지정한다. 운영의 모니터링 마운트는 `../monitoring`, 사전 마운트는
두 환경 모두 `../profanity`로 지정한다. 볼륨 이름은 [환경 계약](../docs/specs/environments.md)에 따른다.

각 `.env`는 `600`으로 설정한다. 기존 APNs 설정과 기기 토큰 암호화 키를 두 환경에서 사용하고 PEM 줄바꿈을 보존한다.
개발은 `bunch-dev`, 운영은 `bunch-prod` R2 키를 넣는다. Cloudinary 키는 직접 이전 작업과 복구용으로 보관한다.
WebSocket은 실제 웹·WebView Origin을 사용한다.

기존 볼륨과 네트워크를 확인하고, 아래 새 자원 이름이 비어 있는 상태에서 한 번 생성한다.

```bash
docker volume inspect bunch-pgdata bunch-redis-data >/dev/null
docker network inspect bunch_default >/dev/null
docker volume create bunch-prod-pgdata
docker volume create bunch-prod-redis-data
```

각 환경에서 `docker compose config --quiet`와 아래 이미지 확인 명령으로 설정과 로컬 이미지를 확인한다.
볼륨 이전 직전 현재 실행 컨테이너와 로컬 `postgres:16`·`redis:7` 이미지가 같은지 확인한다.
개발 Tunnel을 아직 연결하지 않는다면 DB·Redis·앱 서비스만 지정해 기동한다.
공개 전 운영 이미지에서 임시 `/api/v1/auth/dev/token` 경로가 차단되는지 확인한다.

## 3. 점검 시간에 전환

1. 점검 시간에 기존 앱과 Tunnel을 정지한다. 일관된 DB dump와 Redis 전체 볼륨(AOF 포함) 백업을 확보하고
   기존 DB·Redis를 정지한다. 기존 데이터 볼륨은 개발용으로 보존한다. 이때 Grafana 외부 접근도 잠시 중단된다.
2. `~/prod`에서 `docker compose up -d --wait postgres redis`로 기존 `bunch` 프로젝트의 DB·Redis를
   새 운영 볼륨에 연결한다. 실제 마운트가 `bunch-prod-pgdata`·`bunch-prod-redis-data`인지 확인한다.
   아래 순서로 운영 앱 DB를 초기화한 뒤 앱을 기동한다. 빈 DB는 기존 마이그레이션과 R2 V55를 적용한다.
3. `~/prod`에서 Alloy·Loki·Prometheus·Grafana를 기존 볼륨으로 기동한다.
   내부 health·운영 DB·Redis·R2와 대시보드를 확인하고 개발 토큰 API 차단을 확인한 뒤 기존 Tunnel을 기동한다.
   `api.bunch.kr → http://app:8080`, `grafana.bunch.kr → http://grafana:3000`으로 접근되는지 확인한다.
   기존 Tunnel의 다른 API 주소도 운영으로 연결되므로 공개할 주소를 확인한다.
4. 이후 개발 환경을 준비할 때 기존 PostgreSQL·Redis 볼륨을 `~/dev`에 연결한다. 개발 앱 기동 전에
   기존 사진을 직접 R2에 복사·검증하고 자산별 `object_key`를 갱신한다. 운영 DB에는 이 작업을 실행하지 않는다.
5. 같은 앱 이미지로 개발 앱을 기동하고 내부 조회·업로드를 확인한다. 개발 Tunnel과 `dev.bunch.kr`은
   준비된 시점에 연결한다. 테스트 앱의 API·WebSocket 주소도 그때 바꾼다.
   운영 전환부터 개발 주소 연결까지 기존 테스트 앱은 기존 테스트 데이터에 접근할 수 없다.

운영 DB는 최초 한 번만 초기화한다.

```bash
cd ~/prod
docker compose exec postgres psql -h 127.0.0.1 -U postgres -d postgres -W
```

```sql
CREATE USER bunch;
\password bunch
CREATE DATABASE bunch OWNER bunch;
\quit
```

`\password bunch`에는 운영 `.env`의 `DB_PASSWORD`를 입력한다. 이후 `docker compose up -d --pull never --no-deps app`을 실행한다.
기존 데이터가 보이거나 예상 외 볼륨에 연결됐다면 공개를 중단하고 마운트부터 확인한다.

## 4. 이후 앱 배포와 복구

대상 디렉터리 `~/dev` 또는 `~/prod`에서 `.env`의 `APP_IMAGE`에 승인된 GHCR digest를 지정한다.
이미지는 새 main CI가 서버에 내려받는다.

```bash
docker compose config --quiet
docker image inspect "$(docker compose config --images app)" >/dev/null
docker compose up -d --pull never --no-deps app
```

앱 관리 포트의 내부 `/actuator/health`와 해당 도메인으로 배포를 확인한다.
모니터링 설정은 `~/monitoring`에서 관리하고 `~/prod`에서 해당 서비스만 재생성한다.

실패 시 쓰기를 멈추고 이전 이미지·설정·연결을 확인한다. 기존 볼륨을 홈 프로젝트로 되돌릴 때는 먼저
개발 DB·Redis를 정지한다. V55 이후에는 구버전 이미지뿐 아니라 전환 직전 DB 백업 복구가 필요하다.
운영에 이미 새 쓰기를 허용했다면 운영 서비스와 데이터를 보존한 상태에서 복구를 조율한다.
이전 과정에서는 서비스 이름을 지정해 정지·기동한다.
볼륨 삭제와 Cloudinary 원본 삭제는 이 전환 절차에 포함되지 않는다.
