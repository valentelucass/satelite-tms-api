#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd, serde::Serialize)]
pub struct Correlation {
    pub invoice_key: String,
    pub order_number: String,
    pub volume_number: String,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Receipt {
    pub provider_list_id: i64,
    pub created_at: String,
}
