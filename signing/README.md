# Assinatura permanente

Primeira versão: 0.7.0. Pacote: `com.b5solucoes.tcllight`.

Certificado SHA-256: `21e8b079624ca39fcb84817f2a711c46e474d2ccdb65f6e629e26fe153fdb688`.

O arquivo `tcl-light-release.pem` contém somente o certificado público. Não contém a chave privada. Não gere outra chave nas próximas versões e não use `debug.keystore` para distribuição.

O proprietário possui o arquivo privado `TCL-Light-Assinatura-Permanente-Backup-Privado.zip`, com o keystore PKCS#12, alias e senhas. Guarde-o em local privado. Nunca coloque esse ZIP, o keystore, as senhas ou uma URL de acesso ao backup no repositório, nos logs ou nos artifacts públicos.

O workflow do GitHub compila sem a chave. O proprietário baixa o artifact `TCL-Light-Unsigned`, extrai o backup privado fora do repositório e executa:

```sh
python3 tools/sign_release.py --unsigned /caminho/unsigned --private-backup /caminho/privado --output /caminho/signed
```

O script verifica os hashes do artifact e o certificado da chave, assina o APK público e o APK interno de teste e confere os dois. Copie apenas os APKs já assinados e `manifest.json` para `dist/signed/` na branch `signed-releases`. Não copie o backup.

A publicação confere a impressão fixa, exige build release sem depuração, valida pacote/código de versão e testa uma atualização real no emulador. A preferência de Home e uma configuração feita pela interface precisam sobreviver antes que o APK seja publicado.

Para uma nova versão, aumente `versionCode` em `app/build.gradle.kts` e atualize `signing/version.json`; o código do teste interno deve ser maior que o da versão publicada. Mantenha o certificado inalterado.

Se futuramente um administrador cadastrar o keystore e as senhas em GitHub Actions Secrets, a assinatura pode ser automatizada sem mudar a chave. Até lá, a assinatura permanece privada e a validação/publicação são feitas pelo workflow.
