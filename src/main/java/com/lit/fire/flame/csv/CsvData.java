package com.lit.fire.flame.csv;

import java.util.List;
import java.util.Map;

public record CsvData(List<String> headers, List<Map<String, String>> rows) {}
