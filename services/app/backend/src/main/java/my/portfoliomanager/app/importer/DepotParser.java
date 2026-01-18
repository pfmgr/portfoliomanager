package my.portfoliomanager.app.importer;

import java.util.List;

public interface DepotParser {
	List<Position> parse(byte[] payload, String filename, String depotCode, String fileHash);
}
