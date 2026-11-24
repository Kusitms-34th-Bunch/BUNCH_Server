# 개발·운영 분리와 배포

[환경 계약](../docs/specs/environments.md)에 따라 기존 테스트 데이터는 개발로 옮기고
`api.bunch.kr`은 새 빈 운영 환경에서 사용한다. GitHub Actions는 빌드·검증·이미지 게시를 수행한다.

## 1. 현재 앱을 유지하면서 준비

- 기존 `main` workflow가 서버 홈으로 배포하는 동안에는 `dev → main` 릴리스 머지를 보류한다.
  전환 전 기존 CI의 서버 배포를 중지하고 대기·진행 중인 배포가 끝났는지 확인한다.
  새 workflow가 main에 반영된 후에는 검증과 GHCR 게시를 사용할 수 있다.
- #226을 먼저 반영하고 #225를 함께 적용한다. R2는 현재 V53에서 V55로 넘어간다.
  #217을 나중에 반영할 경우 아직 적용하지 않은 V54의 번호를 V55 이후로 조정한다.
  `APP_IMAGE`는 CI 요약의 GHCR digest를 사용한다.
- 현재 홈 Compose·환경 파일·실행 이미지 digest·마운트·볼륨을 확인하고 복구할 수 있게 보관한다.
  DB dump와 Redis 볼륨(AOF 포함)은 별도 저장소에 보관하고 별도 DB/Redis에서 복구를 시험한다.
  기존 Redis의 일관된 전체 볼륨 백업은 쓰기를 멈추고 Redis를 정지한 시점에 확보한다.
- 기존 개발 앱이 동작하는 동안 [#226의 R2 이전 안내](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/pull/226/files)의 준비 SQL, 전체 원본 목록,
  사전 복사를 진행한다. 대상은 `bunch-dev`다. 최초 운영 DB에는 기존 데이터 이전을 실행하지 않는다.

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
개발은 `bunch-dev`, 운영은 `bunch-prod` R2 키를 넣는다. Cloudinary 키는 이전 도구와 복구 설정에 보관한다.
WebSocket은 실제 웹·WebView Origin을 사용한다.

기존 볼륨과 네트워크를 확인하고, 아래 새 자원 이름이 비어 있는 상태에서 한 번 생성한다.

```bash
docker volume inspect bunch-pgdata bunch-redis-data >/dev/null
docker network inspect bunch_default >/dev/null
docker volume create bunch-prod-pgdata
docker volume create bunch-prod-redis-data
```

각 환경에서 `docker compose config --quiet`와 `docker compose pull app`으로 설정·앱 이미지를 확인한다.
볼륨 이전 직전 현재 실행 컨테이너와 로컬 `postgres:16`·`redis:7` 이미지가 같은지 확인한다.
개발 Tunnel을 아직 연결하지 않는다면 DB·Redis·앱 서비스만 지정해 기동한다.
공개 전 운영 이미지에서 임시 `/api/v1/auth/dev/token` 경로가 차단되는지 확인한다.

## 3. 점검 시간에 전환

1. 테스트 앱의 API·WebSocket을 개발 주소로 바꿀 시점을 맞춘다. 홈 Compose에서 앱과 기존 Tunnel을
   정지해 API·웹소켓 쓰기와 관련 백그라운드 작업을 중단한다. 이때 Grafana 외부 접근도 잠시 중단된다.
2. 최종 DB dump를 확보하고 R2 이전 안내의 최종 복사·해시 검증·DB 매핑을 완료한다.
   매핑에는 기존 PostgreSQL이 필요하다. 마무리 후 DB·Redis를 정지하고 최종 Redis 백업을 확보한다.
3. `~/dev`에서 먼저 `docker compose up -d --wait postgres redis`로 기존 볼륨을 연결한다.
   DB·Redis 데이터와 실제 마운트 이름을 확인한 후 `docker compose up -d app`으로 V55를 적용한다.
   기존 계정·사진 조회, 새 업로드·목록 썸네일·상세 원본, WebSocket, 내부 health를 검증한다.
4. `~/prod`에서 `docker compose up -d --wait postgres redis`로 기존 `bunch`의 DB·Redis 서비스를
   **새 운영 볼륨**에 연결한다. 개발과 운영 두 DB·Redis 컨테이너의 실제 마운트 이름이 서로 다른지 확인한다.
   새 PostgreSQL에서 아래 순서로 앱 DB를 생성하고 앱을 기동해 Flyway와 내부 health를 확인한다.
5. `~/prod`에서 Alloy·Loki·Prometheus·Grafana를 기존 볼륨으로 기동한다.
   운영 앱의 Prometheus 대상과 대시보드를 확인한다. `~/prod`에서 기존 Tunnel을 기동해
   `api.bunch.kr → http://app:8080`, `grafana.bunch.kr → http://grafana:3000`으로 접근되는지 확인한다.
   기존 Tunnel의 다른 API 주소도 운영으로 연결되므로 공개할 주소를 확인한다.
   개발 주소는 별도 개발 Tunnel을 기동한 뒤 테스트 앱으로 확인한다.

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

`\password bunch`에는 운영 `.env`의 `DB_PASSWORD`를 입력한다. 이후 `docker compose up -d app`을 실행한다.
기존 데이터가 보이거나 예상 외 볼륨에 연결됐다면 공개를 중단하고 마운트부터 확인한다.

## 4. 이후 앱 배포와 복구

대상 디렉터리 `~/dev` 또는 `~/prod`에서 `.env`의 `APP_IMAGE`에 승인된 GHCR digest를 지정한다.
비공개 이미지는 권한이 있는 계정으로 GHCR에 로그인한다.

```bash
docker compose config --quiet
docker compose pull app
docker compose up -d --no-deps app
```

앱 관리 포트의 내부 `/actuator/health`와 해당 도메인으로 배포를 확인한다.
모니터링 설정은 `~/monitoring`에서 관리하고 `~/prod`에서 해당 서비스만 재생성한다.

실패 시 쓰기를 멈추고 이전 이미지·설정·연결을 확인한다. 기존 볼륨을 홈 프로젝트로 되돌릴 때는 먼저
개발 DB·Redis를 정지한다. V55 이후에는 구버전 이미지뿐 아니라 전환 직전 DB 백업 복구가 필요하다.
운영에 이미 새 쓰기를 허용했다면 운영 서비스와 데이터를 보존한 상태에서 복구를 조율한다.
이전 과정에서는 서비스 이름을 지정해 정지·기동한다.
볼륨 삭제와 Cloudinary 원본 삭제는 이 전환 절차에 포함되지 않는다.
