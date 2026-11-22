# DocScan Pro

- Código Kotlin, interface em português do Brasil.
- Modele regras de página e exportação em `domain/src/main`. Os contratos do domínio ficam livres de classes Android.
- Entrada Android em `app/src/main/java/br/com/docscan/MainActivity.kt`. A UI e seu ViewModel ficam em `ui/`. A implementação local fica em `data/`, com o processamento de imagens em `data/imaging/`.
- Para validar em um comando, execute `./gradlew :domain:test :app:lintDebug :app:assembleDebug`.
- Para testar mudanças no fluxo, importe duas imagens, altere o recorte ou filtro, reordene as páginas, reabra o app e salve ou compartilhe o PDF. Para validar a captura com o ML Kit, use um aparelho com Google Play Services.
