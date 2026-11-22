# Centrifuga+

App Android para controlar lavadoras Midea com Wi-Fi (app **SmartHome**) pela rede local:

- programas da máquina em botões, com temperatura, velocidade e centrifugação extra no final;
- especiais: centrifugar 1, 2 ou 3 vezes, enxágue + centrifugação (e + centrifugar de novo);
- sequências: o app inicia a próxima etapa sozinho quando o ciclo termina;
- centrifugação extra depois de um ciclo iniciado no painel;
- o app aprende quais programas a máquina tem (os que aparecem no status dela).

## Baixar o APK

Cada push gera o APK pelo GitHub Actions (workflow **Gerar APK**). O arquivo
`Centrifuga-mais.apk` fica na página **Releases** do repositório (release `apk-dev`), pronto
para baixar pelo celular. Também fica como artefato da execução do workflow.

## Como funciona

1. Login na conta SmartHome (e-mail e senha), só para listar a lavadora e buscar a chave de
   acesso local dela (token/key). A chave fica salva no celular.
2. Descoberta da lavadora no Wi-Fi (UDP 6445/20086) e conexão direta por TCP (porta 6444).
3. Comandos e status vão direto para a máquina, sem passar pela nuvem.

Suporta lavadoras de abertura superior (tipo `0xDA`) e frontal (`0xDB`).

## Estrutura

- `protocol/` – protocolo Midea em Kotlin puro (criptografia, frames, descoberta, nuvem
  SmartHome, comandos da lavadora), portado da biblioteca open-source
  [midea-local](https://github.com/midea-lan/midea-local). Os testes comparam a saída byte a
  byte com vetores gerados por essa biblioteca.
- `app/` – app Android (sem dependências externas).

Testar só o protocolo, sem Android SDK:

```
PROTOCOL_ONLY=1 ./gradlew :protocol:test
```

## Avisos

- Use por sua conta e risco. O app só usa funções que a própria máquina oferece; os limites de
  segurança dela (trava da porta, balanceamento) continuam valendo.
- A chave de assinatura `app/debug-shared.keystore` é uma chave de depuração sem valor, versionada
  para que cada build possa ser instalado por cima do anterior.
