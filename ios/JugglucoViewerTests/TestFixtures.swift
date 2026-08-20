import Foundation
@testable import JugglucoViewer

enum TestFixtures {
    static let snapshotJSON = Data(
        #"""
        {
          "api_version":"v1",
          "server_time_ms":1800000,
          "from_ms":0,
          "to_ms":1800000,
          "target_range":{"low_mg_dl":75.6,"high_mg_dl":162.0,"low_mmol_l":4.2,"high_mmol_l":9.0},
          "current_glucose":{"reading_id":"cgm-current","measured_at_ms":1500000,"glucose_mg_dl":108.0,"trend_mg_dl_min":-0.5,"sensor_id":"sensor","sensor_generation":"3","quality":0.95,"utc_offset_minutes":180,"received_at_ms":1501000,"age_ms":300000,"is_stale":false},
          "glucose_history":[
            {"reading_id":"cgm-new","measured_at_ms":1500000,"glucose_mg_dl":108.0,"trend_mg_dl_min":-0.5,"sensor_id":null,"sensor_generation":null,"quality":0.95,"utc_offset_minutes":180,"received_at_ms":1501000},
            {"reading_id":"cgm-old","measured_at_ms":1200000,"glucose_mg_dl":126.0,"trend_mg_dl_min":0.2,"sensor_id":null,"sensor_generation":null,"quality":null,"utc_offset_minutes":180,"received_at_ms":1201000}
          ],
          "glucose_history_order":"oldest_first",
          "glucose_history_truncated":true,
          "intake_events":[
            {"id":"77e12a09-acb9-4873-93d7-94521eb10f16","kind":"meal","occurred_at_ms":900000,"meal_text":"Рис","carbs_g":42.0,"portion_g":180.0,"original_portion_g":200.0,"original_carbs_g":46.7,"carbs_source":"manual","insulin_units":null,"insulin_type":null,"insulin_name":null,"ai_confidence":0.8,"absorption_speed":0.6,"absorption_peak_minutes":55,"absorption_duration_minutes":180,"absorption_confidence":0.7,"updated_at_ms":910000}
          ],
          "intake_events_order":"oldest_first",
          "intake_events_truncated":false,
          "forecast":{"status":"ready","generated_at_ms":1800000,"based_on_reading_at_ms":1500000,"based_on_glucose_mg_dl":108.0,"horizon_minutes":120,"model_version":"test-v1","confidence":0.76,"points":[{"at_ms":2100000,"median_mg_dl":105.0,"low_mg_dl":88.0,"high_mg_dl":124.0},{"at_ms":2400000,"median_mg_dl":101.0,"low_mg_dl":80.0,"high_mg_dl":130.0}],"activities":[],"conditional_notice":"Experimental forecast; no treatment recommendation."}
        }
        """#.utf8
    )

    static func snapshot() throws -> ViewerSnapshot {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(ViewerSnapshot.self, from: snapshotJSON).normalized
    }

    static let healthJSON = Data(
        #"{"status":"ok","api_version":"v1","database":"ok","auth_configured":true,"ai_configured":true,"viewer_auth_configured":true}"#.utf8
    )
}
