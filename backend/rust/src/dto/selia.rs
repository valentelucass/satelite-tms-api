use serde::de::Error;
use serde::{Deserialize, Deserializer, Serialize};

fn list_number<'de, D: Deserializer<'de>>(input: D) -> Result<Option<i64>, D::Error> {
    use serde_json::Value;
    match Option::<Value>::deserialize(input)? {
        None => Ok(None),
        Some(Value::Number(n)) => n
            .as_i64()
            .map(Some)
            .ok_or_else(|| D::Error::custom("ID da lista deve ser inteiro")),
        Some(Value::String(s)) if s.trim().is_empty() => Ok(None),
        Some(Value::String(s)) => s.trim().parse().map(Some).map_err(D::Error::custom),
        _ => Err(D::Error::custom("ID da lista inválido")),
    }
}

fn order_reference<'de, D: Deserializer<'de>>(input: D) -> Result<Option<String>, D::Error> {
    use serde_json::Value;
    match Option::<Value>::deserialize(input)? {
        None => Ok(None),
        Some(Value::String(s)) => Ok(Some(s)),
        Some(Value::Number(n)) => Ok(Some(n.to_string())),
        _ => Err(D::Error::custom("Referência de pedido ou volume inválida")),
    }
}

#[derive(Deserialize)]
pub struct PreShipment {
    #[serde(default, deserialize_with = "list_number")]
    pub intelipost_pre_shipment_list: Option<i64>,
    pub shipment_order_array: Option<Vec<Option<Order>>>,
}

#[derive(Deserialize)]
pub struct Order {
    #[serde(default, deserialize_with = "order_reference")]
    pub order_number: Option<String>,
    pub shipment_order_volume_array: Option<Vec<Option<Volume>>>,
}

#[derive(Deserialize)]
pub struct Volume {
    #[serde(default, deserialize_with = "order_reference")]
    pub shipment_order_volume_number: Option<String>,
    pub shipment_order_volume_invoice_array: Option<Vec<Option<Invoice>>>,
}

#[derive(Deserialize)]
pub struct Invoice {
    pub invoice_key: Option<String>,
}

#[derive(Serialize)]
pub struct AcceptedOrder {
    pub order_number: String,
    pub shipment_order_volume_array: Vec<AcceptedVolume>,
}

#[derive(Serialize)]
pub struct AcceptedVolume {
    pub shipment_order_volume_number: String,
}

#[derive(Serialize)]
pub struct PlpMessage {
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub text: &'static str,
    pub key: &'static str,
}

#[derive(Serialize)]
pub struct PlpResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub intelipost_pre_shipment_list: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logistics_provider_shipment_list: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shipment_list_creation_date: Option<String>,
    pub orders_array: Vec<AcceptedOrder>,
    pub status: &'static str,
    pub messages: [PlpMessage; 1],
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hash: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aceita_referencias_numericas_mas_rejeita_id_de_lista_fracionario() {
        let value: PreShipment = serde_json::from_str(r#"{"intelipost_pre_shipment_list":"105","shipment_order_array":[{"order_number":83,"shipment_order_volume_array":[{"shipment_order_volume_number":3}]}]}"#).unwrap();
        assert_eq!(value.intelipost_pre_shipment_list, Some(105));
        let order = value.shipment_order_array.unwrap().pop().unwrap().unwrap();
        assert_eq!(order.order_number.as_deref(), Some("83"));
        let volume = order
            .shipment_order_volume_array
            .unwrap()
            .pop()
            .unwrap()
            .unwrap();
        assert_eq!(volume.shipment_order_volume_number.as_deref(), Some("3"));
        assert!(
            serde_json::from_str::<PreShipment>(r#"{"intelipost_pre_shipment_list":1.75}"#)
                .is_err()
        );
    }
}
