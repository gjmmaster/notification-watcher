(ns notification-watcher.gupshup-service
  (:require [clojure.spec.alpha :as s]
            [clj-http.client :as client]))

;; --- Definição da "Forma" dos Dados (Schema) ---
(s/def ::id string?)
(s/def ::elementName string?)
(s/def ::status #{"APPROVED"})
(s/def ::data string?)
(s/def ::template (s/keys :req-un [::id ::elementName ::status ::data]))


;; --- Funções do Serviço ---
(defn- fetch-raw-data
  "Responsável APENAS por fazer a chamada HTTP."
  []
  (let [api-key (System/getenv "GUPSHUP_API_KEY")]
    (try
      (-> (client/get "https://api.gupshup.io/sm/api/v1/template/list"
                      {:headers {:apikey api-key}
                       :as :json
                       :throw-exceptions false})
          :body)
      (catch Exception e
        (println (str "ERRO: Falha na comunicação com a API da Gupshup: " (.getMessage e)))
        nil))))

(defn- parse-and-clean-templates
  "A Camada Anti-Corrupção em ação: filtra e traduz."
  [raw-data]
  (->> (get-in raw-data [:templates] [])
       (filter #(= "APPROVED" (:status %)))
       (map #(select-keys % [:id :elementName :status :data]))
       (filter #(s/valid? ::template %))
       (doall)))

(defn get-approved-templates
  "A função pública que orquestra e retorna os dados limpos."
  []
  (println "Buscando templates da Gupshup...")
  (if-let [raw-data (fetch-raw-data)]
    (do
      (println "Dados recebidos. Limpando e validando templates...")
      (let [approved-templates (parse-and-clean-templates raw-data)]
        (println (str (count approved-templates) " templates aprovados e prontos para uso."))
        approved-templates))
    []))
