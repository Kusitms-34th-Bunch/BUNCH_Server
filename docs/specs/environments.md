# 개발·운영 환경 (#212)

가비아 서버 한 대와 Linux `bunch` 계정 안에서 두 Compose 프로젝트를 운영한다.
환경 설정은 각각 `~/dev/.env`와 `~/prod/.env`로 관리한다.

| 구분 | 개발 | 운영 |
| --- | --- | --- |
| 브랜치 | `dev` | `main` |
| Compose 프로젝트 | 새 `bunch-dev` | 기존 `bunch` |
| 네트워크 | 새 `bunch-dev_default` | 기존 `bunch_default` |
| PostgreSQL·Redis 볼륨 | 기존 `bunch-pgdata`, `bunch-redis-data` | 새 `bunch-prod-pgdata`, `bunch-prod-redis-data` |
| R2 | `bunch-dev`, 기존 Cloudinary 원본 이전 | `bunch-prod`, 빈 상태로 시작 |
| API·WebSocket | `dev.bunch.kr` | 기존 `api.bunch.kr`과 운영 Tunnel 재사용 |

기존 DB와 Redis를 정지한 뒤 개발 컨테이너에 기존 볼륨을 연결한다. 운영은 새 볼륨과 새 DB·JWT 키를
사용한다. 개발 DB·JWT 값과 양쪽의 기존 APNs 설정·푸시 기기 토큰 암호화 키는 유지한다.
소셜 로그인 앱·번들 분리는 보류한다.
각 환경은 별도 R2 자격 증명을 사용한다. 서버에서 API·DB·Redis·관리 포트를 호스트에 게시하지 않는다.

기존 Alloy·Loki·Prometheus·Grafana는 `~/prod` Compose에서 기존 볼륨과 `~/monitoring` 설정을 사용한다.
모니터링은 기존 운영 네트워크에서 운영 앱을 수집하며 Grafana는 기존 Tunnel을 사용한다.
개발 앱은 Docker 로그와 내부 health로 확인한다.

GitHub Actions는 main에서 검증한 이미지를 GHCR에 게시하고 서버에서 받는다.
초기 배포는 두 환경에 같은 이미지 digest를 수동 적용한다.
서비스 중지·데이터 이전·운영 공개 순서는 [배포 안내](../../deploy/README.md)를 따른다.
