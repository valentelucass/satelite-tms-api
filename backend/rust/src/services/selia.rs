use crate::dto::selia::{AcceptedOrder, AcceptedVolume, PlpMessage, PlpResponse, PreShipment};
use crate::models::selia::{Correlation, Receipt};
use std::collections::HashSet;

pub struct ValidatedList {
    pub id: i64,
    pub orders: Vec<AcceptedOrder>,
    pub correlations: Vec<Correlation>,
}

fn identifier(text: Option<String>) -> Option<String> {
    let raw = text?;
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.encode_utf16().count() > 100 {
        return None;
    }
    Some(trimmed.to_owned())
}

fn non_empty<T>(list: Option<Vec<T>>) -> Option<Vec<T>> {
    list.filter(|items| !items.is_empty())
}

pub fn validate(input: PreShipment) -> Option<ValidatedList> {
    let id = input.intelipost_pre_shipment_list.filter(|id| *id > 0)?;
    let mut orders = Vec::new();
    let mut correlations = Vec::new();
    let mut seen = HashSet::new();
    for order in non_empty(input.shipment_order_array)? {
        let order = order?;
        let order_number = identifier(order.order_number)?;
        let mut volumes = Vec::new();
        for volume in non_empty(order.shipment_order_volume_array)? {
            let volume = volume?;
            let volume_number = identifier(volume.shipment_order_volume_number)?;
            for invoice in non_empty(volume.shipment_order_volume_invoice_array)? {
                let key = identifier(invoice?.invoice_key)?;
                if key.len() != 44 || !key.bytes().all(|digit| digit.is_ascii_digit()) {
                    return None;
                }
                if seen.insert((key.clone(), order_number.clone(), volume_number.clone())) {
                    correlations.push(Correlation {
                        invoice_key: key,
                        order_number: order_number.clone(),
                        volume_number: volume_number.clone(),
                    });
                }
            }
            volumes.push(AcceptedVolume {
                shipment_order_volume_number: volume_number,
            });
        }
        orders.push(AcceptedOrder {
            order_number,
            shipment_order_volume_array: volumes,
        });
    }
    Some(ValidatedList {
        id,
        orders,
        correlations,
    })
}

pub fn accepted(id: i64, orders: Vec<AcceptedOrder>, receipt: Receipt) -> PlpResponse {
    PlpResponse {
        intelipost_pre_shipment_list: Some(id),
        logistics_provider_shipment_list: Some(receipt.provider_list_id),
        shipment_list_creation_date: Some(receipt.created_at),
        orders_array: orders,
        status: "OK",
        messages: [PlpMessage {
            kind: "SUCCESS",
            text: "Operação realizada com sucesso.",
            key: "success.message",
        }],
        hash: Some(format!("SELIA-PLP-{}", receipt.provider_list_id)),
    }
}

pub fn rejected(id: Option<i64>, text: &'static str) -> PlpResponse {
    PlpResponse {
        intelipost_pre_shipment_list: id,
        logistics_provider_shipment_list: None,
        shipment_list_creation_date: None,
        orders_array: Vec::new(),
        status: "ERROR",
        messages: [PlpMessage {
            kind: "ERROR",
            text,
            key: "selia.plp.error",
        }],
        hash: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};

    fn order(number: &str, key: &str) -> Value {
        json!({
            "order_number": number,
            "shipment_order_volume_array": [{
                "shipment_order_volume_number": " V1 ",
                "shipment_order_volume_invoice_array": [{"invoice_key": key}, {"invoice_key": key}]
            }]
        })
    }

    fn list(orders: Value) -> PreShipment {
        serde_json::from_value(
            json!({"intelipost_pre_shipment_list": 1,"shipment_order_array":orders}),
        )
        .unwrap()
    }

    #[test]
    fn preserva_pedidos_e_remove_somente_correlacoes_repetidas() {
        let key = "1".repeat(44);
        let input = list(json!([
            order(" P1 ", &key),
            order("P2", &key),
            order("P1", &key)
        ]));
        let valid = validate(input).unwrap();
        assert_eq!(valid.orders.len(), 3);
        assert_eq!(valid.correlations.len(), 2);
        assert_eq!(valid.correlations[0].order_number, "P1");
        assert_eq!(valid.correlations[0].volume_number, "V1");
        assert_eq!(valid.correlations[1].order_number, "P2");
    }

    #[test]
    fn rejeita_toda_lista_se_o_ultimo_documento_for_invalido() {
        let input = list(json!([order("P1", &"1".repeat(44)), order("P2", "123")]));
        assert!(validate(input).is_none());
    }

    #[test]
    fn identificadores_respeitam_limite_de_cem_unidades_utf16_do_contrato_java() {
        let key = "1".repeat(44);
        assert!(validate(list(json!([order(&"🚛".repeat(50), &key)]))).is_some());
        assert!(validate(list(json!([order(&"🚛".repeat(51), &key)]))).is_none());
    }
}
