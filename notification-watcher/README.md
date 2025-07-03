# Notification Watcher Service

Serviço para monitorar templates da Gupshup e notificar sobre mudanças de categoria.

## Como Configurar

Antes de executar o serviço, você precisa configurar as seguintes variáveis de ambiente:

*   `GUPSHUP_APP_ID`: O ID da sua aplicação na Gupshup.
*   `GUPSHUP_TOKEN`: O token de acesso para a API da Gupshup.

Exemplo:

```bash
export GUPSHUP_APP_ID="seu_app_id"
export GUPSHUP_TOKEN="seu_token"
```

## Como Rodar

Para iniciar o serviço, execute o seguinte comando na raiz do projeto:

```bash
lein run
```

O serviço irá verificar por mudanças nos templates a cada 10 minutos e logar as alterações detectadas no console.
