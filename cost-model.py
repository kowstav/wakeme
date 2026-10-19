"""Wake Me planning model. Run: python cost-model.py [path-to-config.json].

Standard library only. Reads assumptions and prints results; makes no network
requests and changes no files. See document 4 for sources and limitations.
"""
import json
import sys
from pathlib import Path


def calculate(config):
    for field in ("paid_share", "annual_payer_share", "assumed_store_fee"):
        if not 0 <= config[field] <= 1:
            raise ValueError(f"{field} must be between 0 and 1")
    arppu = (
        config["monthly_price"] * (1 - config["annual_payer_share"])
        + config["annual_price"] / 12 * config["annual_payer_share"]
    )
    generation_cost = (
        config["input_tokens_per_generation"] * config["input_price_per_million"]
        + config["output_tokens_per_generation"] * config["output_price_per_million"]
    ) / 1_000_000 * config["retry_and_validation_multiplier"]
    results = []
    for scenario in config["scenarios"]:
        mau = scenario["mau"]
        if mau <= 0:
            raise ValueError("mau must be positive")
        paid = mau * config["paid_share"]
        gross = paid * arppu
        store_fee = gross * config["assumed_store_fee"]
        billing_fee = (
            gross * config["revenuecat_fee"]
            if gross >= config["revenuecat_threshold"] else 0
        )
        generations = (
            (mau - paid) * config["free_generations_per_mau"]
            + paid * config["paid_generations_per_user"]
        )
        ai_cost = generations * generation_cost
        operating = (
            ai_cost + scenario["base_infrastructure"]
            + scenario["support_moderation"]
        )
        results.append({
            "mau": mau,
            "paying_users": paid,
            "gross_recognized_revenue": round(gross, 2),
            "store_fee": round(store_fee, 2),
            "modeled_revenuecat_fee": round(billing_fee, 2),
            "ai_generations": generations,
            "ai_cost": round(ai_cost, 2),
            "service_operating_cost": round(operating, 2),
            "service_cost_per_mau": round(operating / mau, 6),
            "contribution_before_excluded_costs": round(
                gross - store_fee - billing_fee - operating, 2
            ),
        })
    return {
        "currency": config["currency"],
        "recognized_monthly_arppu": round(arppu, 6),
        "ai_generation_cost": round(generation_cost, 6),
        "scenarios": results,
        "limitations": config["limitations"],
    }


if __name__ == "__main__":
    config_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_suffix(".json")
    with config_path.open(encoding="utf-8") as config_file:
        assumptions = json.load(config_file)
    print(json.dumps(calculate(assumptions), indent=2))
