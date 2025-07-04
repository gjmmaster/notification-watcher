# Introduction to notification-watcher

I. Resumo do Projeto
1. Problema a ser Resolvido:
Os usuários de uma aplicação principal não são notificados quando a Meta (Facebook/WhatsApp) altera as regras de seus templates de mensagem (ex: mudança de categoria de UTILITY para MARKETING). Isso causa falhas inesperadas na comunicação dos usuários.

2. Objetivo da Solução:
Criar um serviço automatizado ("Watcher") que monitora a API de um parceiro do WhatsApp (Gupshup) para detectar essas mudanças em tempo real. Uma vez detectada a mudança, o sistema deve, futuramente, notificar os usuários impactados.

3. MVP (Mínimo Produto Viável) Desenvolvido:
Foi criado um serviço autônomo que monitora a API, detecta um tipo específico de mudança (a presença do campo oldCategory em um template) e loga os detalhes do evento no console.

II. Arquitetura da Solução
1. Arquitetura de Aplicação (Lógica):

Serviço Watcher: O serviço principal, escrito em Clojure, responsável por toda a lógica de monitoramento.

Componente de Background: Uma tarefa rodando em uma thread separada (future) que executa um loop infinito. A cada 10 minutos, ela consulta a API da Gupshup.

Componente Web Server: Um servidor web embutido (http-kit) que expõe um único endpoint (GET /) cuja única função é responder a pings externos para manter o serviço "vivo" em ambientes de nuvem com planos gratuitos.

Feature Flag: O serviço possui uma feature flag controlada pela variável de ambiente MOCK_MODE (ou MOCK_MODE_ON) para alternar entre fazer chamadas reais à API e usar um conjunto de dados de teste (mock) hardcoded para depuração.

2. Arquitetura de Deploy (Infraestrutura):

Plataforma: Render.com

Tipo de Serviço: Web Service (no plano gratuito).

Mecanismo de Build: O deploy é feito a partir de um Dockerfile que utiliza um build em múltiplos estágios (multi-stage build) para otimização.

Estágio 1 (Builder): Usa a imagem clojure:lein-2.11.2 para compilar o projeto com Leiningen, gerando um uberjar.

Estágio 2 (Runner): Usa a imagem eclipse-temurin:21-jre-alpine (uma imagem JRE mínima) para executar o uberjar, resultando em uma imagem final leve e segura.

Mecanismo Keep-Alive: O serviço externo UptimeRobot.com está configurado para fazer uma requisição HTTP(S) para a URL pública do serviço no Render a cada 5 minutos. Isso previne que o serviço seja suspenso por inatividade (spin down).

III. Detalhes da Implementação
Linguagem: Clojure 1.11.1

Gerenciador de Projeto: Leiningen

Dependências Principais (project.clj):

clj-http: Para fazer as requisições HTTP para a API da Gupshup.

cheshire: Para processar as respostas JSON.

http-kit: Para criar o servidor web embutido.

compojure e ring/ring-core: Para o roteamento das requisições web.

Lógica Central (core.clj):

A função -main inicia dois processos: o start-watcher-loop! em uma future e o servidor web com server/run-server.

A função fetch-templates contém a lógica da feature flag. Se MOCK_MODE estiver ativo, retorna dados de teste; senão, faz a chamada GET para https://partner.gupshup.io/partner/app/{appId}/templates.

A função check-for-changes itera sobre os templates e procura pela existência da chave "oldCategory".

A função log-change-notification formata e imprime os detalhes da mudança detectada no console (println).

IV. Ambiente de Deploy e Operação
Repositório: https://github.com/gjmmaster/notification-watcher

URL do Serviço: https://jm-notification-watcher.onrender.com

Variáveis de Ambiente Obrigatórias:

GUPSHUP_APP_ID: ID do aplicativo na Gupshup.

GUPSHUP_TOKEN: Token de acesso para a API da Gupshup.

Variável de Ambiente Opcional (para teste):

MOCK_MODE (ou MOCK_MODE_ON): Se o valor for "true", ativa o modo de teste.

V. Estado Atual e Próximos Passos
Estado Atual: O MVP está 100% implantado e operacional no Render. O serviço está monitorando a API real da Gupshup a cada 10 minutos e o UptimeRobot está mantendo o serviço ativo. O teste com dados mockados foi bem-sucedido, validando a lógica de detecção.

Próximo Passo Imediato (Ponto em que paramos): O usuário iniciou o processo de "Go Live" na plataforma da Gupshup para ativar completamente sua Conta de Negócios do WhatsApp (WABA). Ele parou na tela de decisão entre "New phone number" e "Migrate a live phone number".

Próximos Passos do Projeto (Pós-MVP):

Serviço Notificador: Desenvolver um segundo microsserviço que receberá os eventos de mudança do serviço Watcher (via requisição HTTP POST).

Banco de Dados: Este segundo serviço terá um banco de dados para mapear contas (wabaId ou appId) a usuários específicos que devem ser notificados.

Envio de Notificações: Implementar a lógica para enviar a notificação final aos usuários (ex: via WhatsApp).

Serviço de Gerenciamento: Criar um terceiro serviço com uma API CRUD para gerenciar os usuários no banco de dados.

Robustez do Watcher: Melhorar o watcher para detectar outros tipos de mudança (status, conteúdo, etc.) e adicionar um mecanismo de estado mais persistente (ex: Redis) para evitar notificações duplicadas em caso de reinicialização do serviço.

VI. Histórico de Decisões Chave
Web Service vs. Cron Job: Optou-se por um Web Service mantido ativo por um ping externo em vez de um Cron Job, pois a configuração inicial do Render para Cron Jobs e Background Workers solicitou um cartão de crédito, o que o usuário queria evitar na fase de teste gratuito.

Dockerfile: A decisão de usar um Dockerfile foi tomada após o build nativo do Render falhar inicialmente. O Dockerfile implementa um "multi-stage build" para criar uma imagem de produção otimizada.

Feature Flag: A implementação de uma feature flag via variável de ambiente foi escolhida em vez de um "toggle" hardcoded no código para permitir a alternância entre os modos de teste e produção sem a necessidade de um novo deploy.
