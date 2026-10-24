# 최소 서버 배포

`main push → 변경별 검증 → 앱 변경 시 GHCR 게시 → SSH 사용자 홈의 해당 서비스 갱신` 순서다.
dev PR·push의 기존 CI는 유지한다. main에는 검증한 dev를 릴리스 PR로 병합한다.
이미지는 `ghcr.io/kusitms-34th-bunch/bunch_server:latest`이며 Linux x86_64 서버 기준이다.
서버 CPU는 `uname -m`으로 확인한다. ARM 서버면 이미지 빌드 대상 변경이 필요하다.

## 1. 서버 파일 준비

Docker Compose 설치와 Linux `bunch` 사용자 생성이 끝난 상태에서 진행한다.
기존 배포 경로인 bunch 사용자 홈(`~`)을 사용하고 Docker 사용 권한을 부여한다.

```bash
sudo usermod -aG docker bunch
```

`bunch`로 재로그인한 뒤 sudo 없이 `docker ps`가 되는지 확인한다.
Docker 그룹은 root에 준하는 권한이다. SSH 비밀번호 로그인이 가능해야 한다.

- [compose.yaml](compose.yaml)을 서버의 `~/compose.yaml`로 복사한다.
- [monitoring/](monitoring/)를 서버의 `~/monitoring/`에 복사한다. CI도 이 두 설정 경로만 전송한다.
- [.env.example](.env.example)을 서버의 `~/.env`로 복사하고 실제 값을 입력한다.
- 이미 준비한 파일이 있으면 프로젝트명 `bunch`와 볼륨 `bunch-pgdata`, `bunch-redis-data`를 유지한다.
- `.env`의 `POSTGRES_USER=postgres`, `POSTGRES_DB=postgres`는 관리자 초기화용이다.
  앱은 `DB_USERNAME=bunch`, DB `bunch`를 사용한다.
- 앱·DB·Redis·Tunnel·Alloy·Grafana는 같은 `.env`를 `env_file: .env`로 읽는다.
  앱 설정과 모니터링 환경변수는 이 파일 하나에서 관리하며, 파일의 모든 변수가 이 서비스들에 전달된다.
  `APP_IMAGE`는 기존 `ghcr.io/kusitms-34th-bunch/bunch_server:latest`를 유지한다.
- 관리·앱 비밀번호는 서로 다르게 설정한다. `$`·`#`가 있는 값은 작은따옴표로 감싼다.

```bash
cd ~
chmod 600 .env
docker compose config --quiet
docker compose up -d --wait postgres
```

위 명령은 DB만 시작한다. 아직 이미지·Tunnel·소셜 설정을 채우지 않았어도 DB 준비는 가능하다.
실제 `.env`는 Git에 올리지 않으며 값이 출력되는 `docker compose config` 대신 `--quiet`를 사용한다.
CI는 검증 전에 예제를 `deploy/.env`로 복사해 운영과 같은 `env_file` 경로를 검증한다.
이 파일은 CI 작업 공간에서만 사용하며 운영 서버의 `.env`로 전송하지 않는다.

## 2. 앱 DB 계정 생성

신규 DB에서 최초 한 번만 수행한다. 관리자 비밀번호는 `.env`의 `POSTGRES_PASSWORD`다.

```bash
cd ~
docker compose exec postgres psql -h 127.0.0.1 -U postgres -d postgres -W
```

psql 안에서 하나씩 실행한다. `\password bunch`에는 `.env`의 `DB_PASSWORD`와 같은 값을 입력한다.

```sql
CREATE USER bunch;
\password bunch
CREATE DATABASE bunch OWNER bunch;
\connect bunch
GRANT USAGE, CREATE ON SCHEMA public TO bunch;
\quit
```

앱 계정으로 접속한 뒤 사용자와 DB가 모두 `bunch`인지 확인한다.

```bash
cd ~
docker compose exec postgres psql -h 127.0.0.1 -U bunch -d bunch -W
```

```sql
SELECT current_user, current_database();
\quit
```

테이블은 앱 기동 시 Flyway가 생성하고 JPA가 검증한다. 직접 생성하지 않는다.
기존 계정·DB가 있다면 생성 명령을 반복하지 않는다. 기존 볼륨에서는 `.env`를 바꿔도 DB 비밀번호가
변경되지 않으므로 psql의 `\password`로 변경한다. `docker compose down -v`는 데이터를 삭제한다.

## 3. 앱 환경 변수와 Redis 준비

`.env`에 JWT 키와 실제 제공자 값을 채운다. JWT 키 생성 결과는 한 줄로 입력한다.

```bash
openssl rand 64 | openssl base64 -A
```

- `JWT_SECRET`: 디코딩 후 64바이트 이상인 Base64 값. 재배포할 때 유지한다.
- `APPLE_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `KAKAO_NATIVE_APP_KEY`: 실제 앱의 인증 설정.
- `TERMS_*`: 앱 약관 원문과 같은 버전. 가입·온보딩 배포 전 4개를 채운다.
- `CLOUDINARY_*`: 사진 저장 설정. 사진 기능 배포 전에 채운다.
- `BUNCH_INVITATION_BASE_URL`: 실제 공개 초대장 URL 접두사.
- `SPRING_PROFILES_ACTIVE=prod`: Swagger 기본 비활성화.

약관·사진·초대 변수는 가입·온보딩 기능용이며 기존 인증 기반에서도 미리 보관할 수 있다.
DB·Redis에는 호스트 공개 포트가 없다. 앱은 서비스 이름 `postgres`, `redis`로 접속한다.

```bash
cd ~
docker compose up -d --wait postgres redis
```

CI/CD는 `--no-deps app`으로 앱만 교체하므로 **첫 배포 전에 PostgreSQL·Redis를 위 명령으로 준비한다.**
앱이 외부 OIDC 공개키와 Cloudinary에 접근할 수 있도록 DNS·외부 HTTPS 연결도 가능해야 한다.

## 4. GitHub Actions 설정과 배포

저장소 Settings → Secrets and variables → Actions에 다음 Secrets를 등록한다.

| Secret | 값 |
| --- | --- |
| `SSH_HOST` | 서버 IP 또는 SSH hostname |
| `SSH_USER` | `bunch` |
| `SSH_PASSWORD` | Linux bunch 사용자의 비밀번호 |

SSH 포트가 22가 아니면 repository variable `SSH_PORT`를 설정한다.
GitHub runner에서 이 SSH 주소로 접속할 수 있어야 한다.

**GHCR 인증에는 자동 `GITHUB_TOKEN`만 사용한다.** PAT나 `GHCR_TOKEN` Secret을 추가하지 않는다.
워크플로의 `packages: write`로 이미지를 게시하고 같은 토큰을 SSH 표준 입력으로 전달해 서버가 pull한다.
서버에서는 `--password-stdin`으로 로그인하고 작업 종료 시 logout한다. 토큰을 `.env`에 넣지 않는다.
조직의 Packages 게시 정책과 패키지의 저장소 연결·Actions 접근 권한이 이 저장소를 허용해야 한다.
별도 발급하는 Cloudflare `TUNNEL_TOKEN`은 GHCR 인증과 다른 용도다.

CI의 `changes` job은 `git diff`로 두 플래그를 만든다. PR은 merge-base, push는 이전 SHA와 비교한다.
이전 SHA가 모두 0인 최초 push는 둘 다 실행한다. diff 실패는 성공으로 숨기지 않는다.

| 변경 경로 | 앱 | 모니터링 |
| --- | --- | --- |
| `src/`, Gradle 파일·wrapper, Dockerfile·`.dockerignore` | 켬 | 끔 |
| `deploy/monitoring/` 전체(검증·smoke 스크립트 포함) | 끔 | 켬 |
| `deploy/compose.yaml`, `deploy/.env.example`, `.github/workflows/ci.yml` | 켬 | 켬 |
| README·Spec 등 문서만 | 끔 | 끔 |

필수 job 이름 `build`를 유지한다. 둘 중 하나라도 변경되면 실제 PostgreSQL·Redis로 Gradle build를 실행한다.
모니터링 플래그가 켜지거나 변경 감지가 실패하면 `bash deploy/monitoring/check.sh`와
`python3 deploy/monitoring/smoke_test.py`도 실행한다. smoke는 Python3 표준 라이브러리와 Docker로
격리된 Loki/Grafana·HTTP stub를 사용하며 운영 Discord를 호출하지 않는다.
문서만 변경하면 `build`를 skipped로 남긴다. 변경 감지 실패 시 검증 job은 실행하되 배포는 하지 않는다.
워크플로 자체에 경로 필터를 두지 않으며 dev와 PR에서는 게시·SSH를 수행하지 않는다.

main push의 검증 성공 후 다음 순서로 진행한다.

1. 앱 플래그가 켜진 경우만 `latest` 이미지를 빌드·게시한다.
2. SSH의 모든 Compose 명령은 `cd ~`에서 수행한다. `scp`로 `deploy/compose.yaml`을 복사하고,
   모니터링 플래그가 켜진 경우에만 `deploy/monitoring/`을 복사한다.
   앱 단독 변경은 실행 중인 모니터링 설정 파일을 건드리지 않는다. 서버 `.env`는 전송·덮어쓰기하지 않는다.
   전송 전 파일 삭제는 하지 않으며, Git에서 삭제한 설정 파일은 서버에 남을 수 있다.
   파일 삭제가 필요한 변경은 별도 운영 작업으로 확인·정리한 뒤 해당 서비스를 재생성한다.
3. `docker compose config --quiet`가 성공해야 서비스 갱신을 시작한다.
4. 앱 플래그가 켜지면 GHCR에 로그인하고 `docker compose pull app`,
   `docker compose up -d --no-deps app`만 실행한다.
5. 모니터링 플래그가 켜지면 `alloy loki prometheus grafana`를 pull하고
   `docker compose up -d --no-deps --force-recreate alloy loki prometheus grafana`로 함께 갱신한다.
   파일 프로비저닝을 읽도록 네 서비스를 재생성하며, 모니터링 단독 변경은 앱을 재시작하지 않는다.

PostgreSQL·Redis·cloudflared는 CI에서 갱신하거나 재시작하지 않는다.
공통 Compose 변경도 이 세 서비스에 적용해야 한다면 별도 운영 작업으로 수행한다.
SSH와 scp는 매 호출마다 `sshpass -d 3`에 비밀번호 FD를 따로 연결한다.
GHCR 토큰은 SSH stdin으로만 보내고 서버의 `docker login --password-stdin`으로 읽는다.
명령 trace나 환경 덤프를 켜지 않는다. 설정 검증·복사·pull·up 중 오류가 발생하면 즉시 실패한다.
설정 교체는 원자적이지 않으며 앞선 단계가 성공한 상태로 남을 수 있다.
원인을 고친 다음 GitHub Actions에서 해당 workflow를 수동 재실행한다. 자동 복원은 없다.

참고 저장소와 동일하게 SSH는 `StrictHostKeyChecking=accept-new`를 사용한다.
매 실행의 새 runner는 처음 받은 서버 키를 신뢰하므로 고정 서버 키 대조는 수행하지 않는다.
`latest` 하나를 사용하며 자동 롤백과 앱 HTTP 준비 상태 검사는 포함하지 않는다.
워크플로 성공은 앱 교체 명령 성공까지다. 실제 기동·API 응답은 아래 단계에서 확인한다.

## 5. Cloudflare Tunnel과 확인

Cloudflare에서 원격 관리 Tunnel을 만들고 토큰을 `.env`의 `TUNNEL_TOKEN`에 입력한다.
공개 hostname의 원본 서비스는 **`http://app:8080`**으로 설정한다.
`SERVER_PORT`를 변경하면 원본 서비스 포트도 함께 바꾼다.
앱·DB·Redis의 호스트 포트를 열거나 별도 Nginx·인증서 컨테이너를 추가할 필요가 없다.

첫 main 배포가 끝나면 서버에서 Tunnel을 기동한다.

```bash
cd ~
docker compose up -d --no-deps cloudflared
docker compose ps
docker compose logs --tail 50 app cloudflared
```

앱 로그의 기동 완료와 Flyway 적용, Tunnel 연결을 확인하고 공개 HTTPS 주소에서 실제 로그인·API를 확인한다.
관리 포트 `8081`에서만 `/actuator/health`, `/actuator/prometheus`를 제공한다.
공개 API hostname과 앱 `8080`에서는 관리 데이터를 조회할 수 없어야 한다.
보호된 API의 401 또는 Prometheus `up=1`만으로 DB·Redis 정상 동작을 판단하지 않는다.
WebView JavaScript에서 다른 출처 API를 호출한다면 CORS·OPTIONS 설정은 별도 앱 연동 작업이다.

## 6. 모니터링 최초 준비와 확인

서버 `.env`에 `DISCORD_WEBHOOK_URL`, `GF_SERVER_ROOT_URL`, `GF_SECURITY_ADMIN_PASSWORD`를 준비한다.
Grafana 관리자 이름 등 나머지 변수는 [환경 예제](.env.example)를 따른다.
`GF_SERVER_ROOT_URL`은 관리자용 실제 HTTPS URL이다. 관리자 비밀번호는 초기 계정 생성용이며,
이미 생성한 계정은 Grafana에서 별도로 변경한다. 운영 웹훅을 로컬·CI에 복사하지 않는다.

Cloudflare Tunnel에 관리자 hostname → `http://grafana:3000`을 별도로 추가한다.
`app:8081`·Loki·Prometheus를 공개 hostname에 연결하지 않는다.
모니터링 기동 전 RAM·디스크 여유와 설정을 확인한다.

```bash
cd ~
docker compose config --quiet
docker compose pull alloy loki prometheus grafana
docker compose up -d --no-deps --force-recreate alloy loki prometheus grafana
docker compose ps
```

Grafana 로그인 후 프로비저닝된 대시보드 하나에서 `up`·요청량·5xx·p95·JVM·DB 풀과
요청 로그를 확인한다. 앱 로그의 eventId/traceId로 검색하고 알림 규칙이 두 개인지 확인한다.
로그·메트릭은 7일, Docker 로그는 파일당 `10m`, 최대 3개다.
최초 pull·기동·프로비저닝·첫 수집이 끝나기 전에는 탐지되지 않는다.
5xx는 최근 1분 종료 로그 한 건부터 다음 10초 평가에서 알리며 별도 pending·묶음 대기가 없다.
로그 읽기·멀티라인·전송·조회 지연이 더해져 10초 내 Discord 수신을 보장하지 않는다.
앱 수집 실패는 1분 유지 후 발생/복구를 보내고, 5xx는 복구를 보내지 않는다. 지속 장애 반복은 5분이다.
세부 상태·한계·근거는 [모니터링 Spec](../docs/specs/monitoring.md)을 따른다.

격리된 로컬/CI 검증은 저장소 루트에서 실행한다. Docker와 Python3가 필요하며,
HTTP stub 기반 검증은 실제 Discord 수신과 다르다.

```bash
# 로컬에 .env가 없는 최초 실행에서만 예제를 복사한다.
test -f deploy/.env || cp deploy/.env.example deploy/.env
bash deploy/monitoring/check.sh
python3 deploy/monitoring/smoke_test.py
```

운영 Discord 시험은 지정한 시험 채널·웹훅으로 별도 확인한다. 원문 로그·환경 변수 출력은
웹훅/토큰 등 민감값이 없는지 확인한 뒤 제한적으로 다룬다. 같은 서버의 Grafana 자체가
중단되면 알림도 중단되며 외부 접속·Tunnel·호스트 전체 장애를 감지하는 구성은 아니다.

## 7. 실패 후 수동 설정 복원

서비스 갱신 실패는 자동으로 되돌리지 않는다. 단순 재시도라면 원인을 고친 뒤 workflow를 재실행한다.
이전 설정이 필요하면 **운영 서버가 아닌 로컬 저장소**에서 확인한 이전 커밋의 파일을 꺼낸다.
작업 중 변경이 있는 공유 저장소에서는 먼저 보존하거나 별도 clone을 사용한다.

```bash
# PREVIOUS_COMMIT은 운영자가 확인한 이전 정상 설정 커밋이다.
git checkout "$PREVIOUS_COMMIT" -- deploy/compose.yaml deploy/monitoring/
```

`git checkout`은 새 커밋에서만 추가된 파일을 제거하지 않을 수 있다. 복원할 파일 목록을 확인하고,
서버에서는 선택한 커밋의 모니터링 파일을 `~/monitoring/`에 복사한다.
로컬 복원 디렉터리에 남은 새 파일도 제외해 이전 커밋의 파일만 복사한다.
전송 성공 전에 실행 중인 설정 파일을 삭제하지 않는다. 이전 커밋에서 제거해야 할 서버 파일은
전송 완료 후 별도로 확인·정리하고 아래 명령으로 모니터링 서비스를 재생성한다.
복원한 `deploy/compose.yaml`은 `~/compose.yaml`에 복사한다. 서버 `.env`는 보존한다.
명령을 실행하기 전 올바른 SSH 사용자 홈(`~`)과 Git 커밋인지 확인한다.

```bash
cd ~
docker compose config --quiet
docker compose pull alloy loki prometheus grafana
docker compose up -d --no-deps --force-recreate alloy loki prometheus grafana
docker compose ps
```

이 절차는 설정 파일 복원이며 앱 바이너리 복원은 아니다. `latest`를 다시 pull해도 이전 앱 이미지가
보장되지 않는다. 앱 변경 복구는 수정/되돌림 커밋을 정상 CI로 재빌드·배포한다.
별도 digest·manifest·staging·자동 rollback 체계는 없다. 파일 복원·재시도 중에는 일부 서비스만
새 설정일 수 있으므로 앱 수집과 Grafana를 다시 확인한다.
`docker compose down -v`, `docker volume rm`, DB·Redis 데이터 경로 삭제는 수행하지 않는다.

## 참고

- [hnc-qna 배포 근거](https://github.com/wingwogus/hnc-qna/tree/main/docs/evidence)
- [hnc-qna 워크플로](https://github.com/wingwogus/hnc-qna/blob/main/.github/workflows/ci-cd.yml)
- [GHCR와 GITHUB_TOKEN](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/get-started/)
