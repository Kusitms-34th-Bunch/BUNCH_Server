# 최소 서버 배포

`main push → build·test → GHCR 게시 → SSH로 /bunch의 app 교체` 순서다.
dev PR·push의 기존 CI는 유지한다. main에는 검증한 dev를 릴리스 PR로 병합한다.
이미지는 `ghcr.io/kusitms-34th-bunch/bunch_server:latest`이며 Linux x86_64 서버 기준이다.
서버 CPU는 `uname -m`으로 확인한다. ARM 서버면 이미지 빌드 대상 변경이 필요하다.

## 1. 서버 파일 준비

Docker Compose 설치와 Linux `bunch` 사용자 생성이 끝난 상태에서 진행한다.
관리자 계정으로 배포 폴더를 만들고 bunch에 Docker 사용 권한을 부여한다.

```bash
sudo install -d -o bunch -g bunch -m 700 /bunch
sudo usermod -aG docker bunch
```

`bunch`로 재로그인한 뒤 sudo 없이 `docker ps`가 되는지 확인한다.
Docker 그룹은 root에 준하는 권한이다. SSH 비밀번호 로그인이 가능해야 한다.

- [compose.yaml](compose.yaml)을 서버의 `/bunch/compose.yaml`로 복사한다.
- [.env.example](.env.example)을 서버의 `/bunch/.env`로 복사하고 실제 값을 입력한다.
- 이미 준비한 파일이 있으면 프로젝트명 `bunch`와 볼륨 `bunch-pgdata`, `bunch-redis-data`를 유지한다.
- `.env`의 `POSTGRES_USER=postgres`, `POSTGRES_DB=postgres`는 관리자 초기화용이다.
  앱은 `DB_USERNAME=bunch`, DB `bunch`를 사용한다.
- 모든 서비스가 `env_file: .env`를 읽으므로 같은 파일의 모든 값이 네 컨테이너에 전달된다.
  `APP_IMAGE`는 Compose가 같은 `.env`에서 읽어 이미지 주소에 대입한다.
- 관리·앱 비밀번호는 서로 다르게 설정한다. `$`·`#`가 있는 값은 작은따옴표로 감싼다.

```bash
cd /bunch
chmod 600 .env
docker compose config --quiet
docker compose up -d --wait postgres
```

위 명령은 DB만 시작한다. 아직 이미지·Tunnel·소셜 설정을 채우지 않았어도 DB 준비는 가능하다.
실제 `.env`는 Git에 올리지 않으며 값이 출력되는 `docker compose config` 대신 `--quiet`를 사용한다.

## 2. 앱 DB 계정 생성

신규 DB에서 최초 한 번만 수행한다. 관리자 비밀번호는 `.env`의 `POSTGRES_PASSWORD`다.

```bash
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

워크플로는 main push마다 테스트·빌드가 성공한 경우만 `latest`를 게시하고 앱을 교체한다.
코드·설정 변경을 놓치지 않도록 경로 필터를 두지 않는다. dev와 PR에서는 게시·SSH 단계를 건너뛴다.
서버의 `.env`·Compose·DB·Redis·Tunnel은 자동으로 덮어쓰거나 재시작하지 않는다.
배포 설정 변경 시 서버 파일도 직접 갱신한다. 이미지는 `.env`의 `APP_IMAGE`와 같은 주소를 사용한다.

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
cd /bunch
docker compose up -d --no-deps cloudflared
docker compose ps
docker compose logs --tail 50 app cloudflared
```

앱 로그의 기동 완료와 Flyway 적용, Tunnel 연결을 확인하고 공개 HTTPS 주소에서 실제 로그인·API를 확인한다.
현재 `/actuator/health`는 제공하지 않는다. 보호된 API의 401 응답만으로 DB·Redis 정상 동작을 판단하지 않는다.
WebView JavaScript에서 다른 출처 API를 호출한다면 CORS·OPTIONS 설정은 별도 앱 연동 작업이다.

## 참고

- [hnc-qna 배포 근거](https://github.com/wingwogus/hnc-qna/tree/main/docs/evidence)
- [hnc-qna 워크플로](https://github.com/wingwogus/hnc-qna/blob/main/.github/workflows/ci-cd.yml)
- [GHCR와 GITHUB_TOKEN](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Cloudflare Tunnel](https://developers.cloudflare.com/tunnel/get-started/)
