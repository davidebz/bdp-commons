package it.bz.idm.bdp.spreadsheets;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;

import it.bz.idm.bdp.dto.StationDto;
import it.bz.idm.bdp.dto.StationList;

@Service
public class JobScheduler {

	@Autowired
	private GoogleSpreadSheetDataFetcher googleClient;

	@Autowired
	private ODHClient odhClient;

	@Autowired
	private DataMappingUtil util;

	@Value("${spreadsheetId}")
	private String origin;

	@Value("${headers.addressId}")
	private String addressId;


    @Value("${spreadsheet.sheets.names}")
    private String[] sheetsIdentifier;
	/**
	 * scheduled job which syncs odh with the spreadsheet
	 */
	public void syncData() {
		googleClient.authenticate();
		Spreadsheet fetchedSheet = (Spreadsheet) googleClient.fetchSheet();
		for (Sheet sheet : fetchedSheet.getSheets()){
			if (Arrays.asList(sheetsIdentifier).contains(sheet.getProperties().getTitle())) {
				try {
				List<List<Object>> values = googleClient.getWholeSheet(sheet.getProperties().getTitle()).getValues();
				List<StationDto> odhStations = odhClient.fetchStations(odhClient.getIntegreenTypology(), origin);
				StationList dtos = mapData(values, odhStations, sheet.getProperties().getSheetId());
				odhClient.syncStations(dtos);
				}catch(Exception ex) {
					ex.printStackTrace();
					continue;
				}
			}
		}

	}

	/**
	 * @param spreadSheetValues values to map to a {@link StationDto}
	 * @param existingOdhStations {@link StationDto}'s of this spreadsheet which already exist in odh
	 * @return list of all valid rows in form of {@link StationDto}
	 */
	private StationList mapData(List<List<Object>> spreadSheetValues, List<StationDto> existingOdhStations, Integer sheetId) {
		StationList dtos = new StationList();
		Map<String, Short> headerMapping = null;
		Set<Integer> missingPositions = new HashSet<>();
		int i = 0;
		for (final List<Object> row : spreadSheetValues) {
			if (i == 0) {
				headerMapping = util.listToMap(row);
				if (headerMapping.isEmpty() || !(headerMapping.containsKey("address")||(headerMapping.containsKey("longitude") && headerMapping.containsKey("latitude")))){
					throw new IllegalStateException("Spreadsheet does not conform to rules");
				}
			} else {
				StationDto dto = null;
				try {
					dto = odhClient.mapStation(headerMapping, row);
				}catch(Exception ex) {
					i++;
					continue;
				}
				if (!existingOdhStations.contains(dto) && dto.getLongitude() == null) {
					try {
						odhClient.guessPositionByAddress(dto);
					}catch(final IllegalStateException ex) {
						missingPositions.add(i);
						i++;
						continue;
					}
				}
				odhClient.guessLanguages(dto.getMetaData());
				odhClient.mergeTranslations(dto.getMetaData(),headerMapping);
				if (dto.getName()==null || dto.getName().isEmpty())
					dto.setName(dto.getId());
				if (existingOdhStations.contains(dto)||(dto.getLongitude() != null && dto.getLatitude() != null)) {
					dtos.add(dto);
				}
			}
			i++;
		}
		if (headerMapping.get(addressId) != null) {
			googleClient.markMissing(missingPositions,headerMapping.get(addressId).intValue(),sheetId);
		}
		return dtos;
	}


}
