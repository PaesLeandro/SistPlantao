# SistPlantão Pro (PWA)

Agenda e escala de plantões offline-first, instalável como app (PWA) e pronta para empacotar em TWA/Play Store.

## Demo
- Produção: https://sistplantao.vercel.app/

## Funcionalidades
- Escala fixa e compromissos avulsos (diário/extra).
- Padrão alternado 12h dia → 12h noite (dia seguinte) → 72h descanso.
- Exportação: PDF do calendário e arquivo ICS (Google/Outlook).
- Tema claro/escuro; paleta de cores nos turnos.
- PWA install prompt + offline com fallback `offline.html`.
- Banner avisando que os dados são locais (localStorage).

## Estrutura
- `index.html` — UI + lógica.
- `sw.js` — cache de assets externos, offline fallback, atualização assistida.
- `manifest.json` — metadados PWA (ícones 192/512).
- `offline.html` — página sem rede.
- `.well-known/assetlinks.json` — placeholder para TWA (preencher SHA256 e package).
- `vercel.json` — headers para SW/manifest e cache de offline.
- `icons/` — ícones PNG exigidos pelo manifest.

## Rodar local
```bash
npm install -g serve
serve -s .
# http://localhost:3000
```
Para o service worker, sirva via HTTPS ou `serve --ssl` com certificado local.

## Deploy (Vercel)
1. Importar do GitHub (framework: Other, root `./`, build vazio, output vazio).
2. Deploy automático a cada push na `main`.
3. Testar manifest, sw e instalação PWA.

## Play Store via TWA
1. HTTPS e PWA instalável (Lighthouse verde).
2. `npm i -g @bubblewrap/cli`
3. `bubblewrap init --manifest=https://sistplantao.vercel.app/manifest.json` (defina `packageId`).
4. `bubblewrap build` → `.aab`.
5. Ajuste `.well-known/assetlinks.json` com packageId + SHA256 do keystore; redeploy no Vercel.
6. Publique o `.aab` no Play Console (Play App Signing).

## Licença
MIT — veja `LICENSE`.
