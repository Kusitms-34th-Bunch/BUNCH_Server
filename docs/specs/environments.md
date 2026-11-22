# 개발·운영 실행 환경 계약 (#212)

## 확정 범위

가비아 4vCPU/8GB 단일 서버와 Linux `bunch` 계정을 유지한다. Kubernetes·추가 인스턴스·서버 빌드를 도입하지 않는다.
실제 적용 순서와 명령은 [배포 안내](../../deploy/README.md)를 따른다. 아래 구성은 실제 적용 완료를 뜻하지 않는다.

| 구분 | 개발 | 운영 | 공용 관측 |
| --- | --- | --- | --- |
| 경로 | `~/dev` | `~/prod` | `~/monitoring` |
| 브랜치 | `dev` | `main` | `main`의 관측 변경만 별도 활성화 |
| Compose 프로젝트 | 기존 실측 이름 유지(현재 설정 `bunch`) | `bunch-prod` | 기존 `bunch` 유지, 별도 Compose 파일·관측 전용 네트워크 |
| API·WebSocket | `dev.bunch.kr` | `api.bunch.kr` | 관리자 전용 hostname |
| DB·Redis 볼륨 | 기존 실측 이름, external | 새 `bunch-prod-pgdata`, `bunch-prod-redis-data`, external | 기존 모니터링 볼륨 이름 유지 |
| 네트워크 | 기존 개발 네트워크 유지 | 독립 `bunch-prod-default` | dev/prod 관측 네트워크 각각에 Prometheus만 접속 |
| R2 버킷 | `bunch-dev` | `bunch-prod` | 사용하지 않음 |

앱·PostgreSQL·Redis·Tunnel은 환경마다 독립 실행한다. 앱만 자기 환경의 관측 네트워크에도 연결하고
`dev-app` / `prod-app` alias를 쓴다. DB/Redis와 관리 포트는 호스트에 게시하지 않는다.
서로 다른 앱이 같은 네트워크에 들어가지 않으며 공용 관측 서비스는 DB/Redis에 접속하지 않는다.
기존 프로젝트·볼륨을 외관상 이름 변경하려고 재생성하지 않는다. `down -v`·prune·볼륨 삭제는 금지한다.
운영 DB는 빈 DB로 시작하고 Flyway로 생성한다. 개발 DB나 Redis를 운영으로 복제하지 않는다.

## 환경·이미지·관측

- `.env`는 환경 디렉터리에 운영자가 생성하며 CI가 전송·출력하지 않는다. JWT, DB 암호, R2 키, Tunnel 토큰은 환경별로 다르다.
- R2 계약은 `R2_ENDPOINT`(버킷명 제외), `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`다. 서명 기본 만료는 #213 코드의 1800초다.
- Apple/Google/Kakao 앱·번들 ID 분리는 보류한다. Cloudinary는 #213 앱과 데이터 이전 검증 후 제거한다.
- 앱은 CI가 게시한 SHA 태그를 pull한 뒤 digest로 고정한다. 환경별 `.release.env`가 `APP_IMAGE`를 기록하며 서로 공유하지 않는다.
- 앱 환경의 PostgreSQL·Redis·Tunnel 이미지도 실측 digest로 고정하고 CI가 갱신하지 않는다. 공용 관측은 정확한 버전 또는 digest를 사용한다.
- 컨테이너 자원 상한 합계는 4CPU/8GB보다 작게 두고 OS·Docker 여유를 남긴다. 상한은 실측 후 조정하며 성능 보장은 아니다.
- 단일 Alloy/Loki/Prometheus/Grafana가 `environment=dev|prod` 라벨로 로그·메트릭·알림·대시보드를 구분한다.
  기존 [모니터링 Spec](monitoring.md)의 타이밍·로그 보관·수집 비밀값 정책을 유지하며 단일 환경·공통 env_file 설명은 이 계약이 대체한다.
- 공용 모니터링의 별도 `.env`에는 DB/JWT/R2 키를 넣지 않는다. 각 서비스에는 필요한 값만 전달한다.

## 전환과 실패 계약

1. 이전 `main → ~` 배포를 중지하고 실행 중 배포가 없는지 확인한다. 새로운 자동 배포는 준비 표식 없이는 실패해야 한다.
2. 기존 프로젝트·네트워크·볼륨·이미지·bind mount를 비밀값 없이 확인한다. 복구 가능한 백업을 별도 보관하고 복구 시험을 마친다.
3. 개발 경로만 이전하고 기존 DB/Redis 데이터를 재사용한다. 관측 파일을 옮기기 전 Alloy/Loki/Prometheus/Grafana만 잠깐 중단한다.
4. 개발 hostname과 테스트 앱 API·WebSocket 변경을 실제 검증한다. 기존 `api` 경로는 이 확인 전 유지한다.
5. 개발 R2 전환(#213)을 검증한다. 운영은 새 DB/Redis·키·R2·Tunnel로 준비하고 준비 표식을 마지막에 생성한다.
6. 운영 검증 후 `api` hostname을 운영 Tunnel로 전환한다. dev/main 각각의 자동 배포를 별도 활성화한다.

실패 시 새 배포를 중지하고 이전 고정 이미지·설정으로 앱만 복구한다. Flyway가 변경한 스키마는 이미지 rollback만으로 되돌아가지 않는다.
DB 복원은 별도 검증한 백업과 쓰기 중단이 필요하다. 데이터 볼륨과 기존 홈 파일은 성공 확인 전 삭제하지 않는다.

## 완료 기준

- Compose 격리·필수 변수·볼륨 미생성·자원 상한·CI 분기/게이트 검증, 실제 모니터링 smoke, 전체 build 결과를 보고한다.
- 실제 서버 자원·외부 hostname·iOS WebSocket·복구·Discord 수신은 조정 채팅의 수동 검증으로 구분한다.
- CONTRIBUTING의 과거 `dev 검증만/main 홈 배포` 설명은 이 이슈 전환 후 이 계약과 배포 안내가 대체한다. 해당 문서의 편집은 소유 범위 밖이다.

서버 읽기 전용 확인(2026-11-22, 조정 채팅): bunch 계정·프로젝트, `bunch-pgdata` / `bunch-redis-data`, `/home/bunch/compose.yaml` 및 `.env` 경로 확인. 모니터링 볼륨 이름·이미지 digest는 적용 시 재확인한다.
