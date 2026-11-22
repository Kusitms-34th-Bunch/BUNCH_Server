# 공용 모니터링 (#212)

## 변경 계획과 유지 동작

- 기존 단일 환경 smoke를 앱 Compose 의존 부분만 기존 고정 이미지로 대체해 먼저 실행하고 기준 동작을 확인한다.
- 기존 고정 이미지·볼륨·7일 보관·15초 scrape·10초 평가·5xx 즉시/앱 중단 1분/5분 반복 계약을 유지한다.
- 관측 Compose를 분리하고 외부 볼륨 이름을 필수 입력으로 받아 기존 데이터를 재사용한다.
- 앱의 `kr.bunch.environment` 라벨로 로그를 분리하고 두 외부 metrics 네트워크에는 Prometheus만 연결한다.
- 환경별 네 알림과 통합 dashboard selector를 실제 Loki/Prometheus/Grafana 및 로컬 webhook으로 검증한다.
- 운영 웹훅·서버·공유 볼륨을 변경하지 않는다. 테스트는 고유 이름의 자원만 생성·삭제한다.

## 적용 전제와 실행

이 디렉터리의 내용 전체를 `~/monitoring`에 배치한다. `.env.example`은 검증용 가짜 값이며,
실제 `.env`에는 DB/JWT/R2 키를 넣지 않는다. `env_file` 없이 Grafana와 전용 Tunnel에 필요한 값만 전달한다.
모니터링 상한 합계는 **0.8 CPU / 1152MiB**다. 메모리 상한은 데이터량·쿼리량에 따라 OOM이 생길 수 있으므로 서버 적용 후 관측한다.

1. 기존 자동 배포를 중단한 상태에서 기존 `bunch` project의 네 모니터링 컨테이너 mount를 확인한다.
   `docker inspect --format '{{range .Mounts}}{{if eq .Type "volume"}}{{println .Name .Destination}}{{end}}{{end}}' <컨테이너>`처럼
   volume 이름과 destination만 출력하며 전체 inspect나 `.env` 출력은 하지 않는다.
2. `MONITORING_PROJECT`와 `ALLOY_VOLUME / LOKI_VOLUME / PROMETHEUS_VOLUME / GRAFANA_VOLUME`에 **실측 이름**을 설정한다.
   예제의 `bunch_alloydata / bunch_lokidata / bunch_prometheusdata / bunch_grafanadata`가 실제 이름이라는 보장은 없다.
   프로젝트는 반드시 `bunch`이며 볼륨 네 개는 서로 달라야 하고 DB/Redis 볼륨 이름을 사용할 수 없다.
   각각 `docker volume inspect --format '{{.Name}}' <실측이름>`으로 존재를 확인한다. external 설정은 없는 볼륨을 자동 생성하지 않는다.
3. 기존 볼륨의 복구 가능한 백업을 확보하고, `bunch-dev-metrics / bunch-prod-metrics`와
   `MONITORING_NETWORK`에 지정한 네트워크를 준비한다. 공용 network는 반드시 외부 `bunch-monitoring`을 사용한다.
   기존 `bunch_default`는 개발 DB/Redis가 있어 사용하지 않는다. 모니터링은 앱 DB/Redis에 연결되지 않는다.
4. Grafana 관리자 hostname만 `http://grafana:3000`으로 연결하는 **별도 Tunnel**과 해당 토큰을 준비한다.
   Tunnel은 모니터링 기본 network에만 연결하며 앱의 network에는 접속하지 않는다. 앱용 Tunnel 토큰을 재사용하지 않는다.
   `MONITORING_TUNNEL_IMAGE`는 서버에서 검증한 정확한 버전/digest를 입력한다.
   예제의 [2026.10.0](https://github.com/cloudflare/cloudflared/releases/tag/2026.10.0)은 고정 버전 예시이며 자동 업그레이드 지시가 아니다.
5. 새 경로에서 다음을 실행해 비밀값 출력 없이 설정을 확인한다.

   ```sh
   python3 config_test.py --env-file .env
   docker compose --env-file .env -f compose.yaml config --quiet
   ```

6. **짧은 중단 지점:** 기존 Compose 경로에서 `stop alloy loki prometheus grafana`만 실행한다.
   새 경로에서 `docker compose --env-file .env -f compose.yaml up -d --no-deps alloy loki prometheus grafana`로
   네 서비스를 같은 프로젝트·같은 볼륨으로 재생성한다. 두 Compose 파일의 orphan 경고는 예상되며
   `--remove-orphans`, `down`, `down -v`, prune은 사용하지 않는다. 앱·DB·Redis·기존 앱 Tunnel을 중단하지 않는다.
7. 같은 명령으로 `monitoring-cloudflared`만 별도 기동하고 관리자 hostname을 확인한다.
   개발/운영 앱이 각 `dev-app / prod-app:8081`에서 수집되는지와 해당 환경 로그를 확인한다.
   운영 앱이 아직 없으면 prod 수집 중단 알림이 예상된다. 파일상 운영 규칙을 임의로 삭제하지 말고,
   초기 준비 작업 중 발생할 수집 중단 알림과 수신 채널을 운영자가 미리 조율한다.

기존 Grafana DB의 이전 두 UID 알림은 `rules.json`의 `deleteRules`로 삭제해 중복 알림을 막는다.
새 네 규칙은 dev/prod별로 독립 평가하며 기존 알림 타이밍을 유지한다. 기존 과거 메트릭은
`environment` 라벨이 없어 새 selector에 나타나지 않지만 볼륨 안에 유지된다.
기존 로그의 환경 라벨은 이전 설정값 그대로이며 과거 개발 로그에 `prod`가 붙었을 수도 있다.
과거 로그를 임의로 재작성하지 않으며, 환경별 검증은 전환 시각 이후 구간에서 수행한다.

실패하면 새 `monitoring-cloudflared` 및 네 서비스만 중단하고 이전 고정 이미지·설정·bind mount 경로로
네 서비스를 재기동한다. 이때 이전 alert provisioning 디렉터리에 다음 임시 파일을 추가해 새 네 규칙도 삭제한다.
이전 설정만 복원하면 Grafana DB에 새 규칙이 남아 중복 알림이 생길 수 있다.

```json
{"apiVersion":1,"deleteRules":[{"orgId":1,"uid":"bunch-http-5xx-dev"},{"orgId":1,"uid":"bunch-app-down-dev"},{"orgId":1,"uid":"bunch-http-5xx-prod"},{"orgId":1,"uid":"bunch-app-down-prod"}]}
```

파일 이름은 `rollback-delete.json`으로 하고 이전 Grafana 기동 뒤 새 네 UID 삭제·기존 두 UID 재생성을 확인한다.
다시 새 구성을 적용할 때는 이 임시 파일을 제거한다. 같은 버전을 유지하므로 볼륨 복원을 먼저 시도하지 않는다. 데이터 손상 복원은 쓰기 중단·검증한 백업을 전제로 별도 진행한다.

## 비밀값 없는 로컬 검증

```sh
sh deploy/monitoring/check.sh
python3 deploy/monitoring/smoke_test.py
```

첫 명령은 `.env.example`만 사용해 Compose·리소스·네트워크·Alloy·Loki·Prometheus 설정을 검사한다.
두 번째는 고유 이름의 일회성 Docker 자원과 localhost webhook stub만 사용해 실제 Grafana/Loki/Prometheus에서
두 환경의 알림 격리·기존 탐지/반복/복구 계약을 검증한다. 운영 webhook과 기존 영속 볼륨을 사용하지 않는다.
Alloy Docker 라벨 수집도 임시 앱 컨테이너를 대상으로 검증한다.
